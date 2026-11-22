/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Le Bao Uyen Truong a1909745
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int WORKER_COUNT = Math.max(
            1,
            Integer.getInteger(
                    "video.workers",
                    Math.max(
                            2,
                            Math.min(
                                    6,
                                    Runtime.getRuntime().availableProcessors() - 2))));
    private static final int MAX_IN_FLIGHT = WORKER_COUNT * 2;

    private final List<FrameProcessor> frameProcessors;
    private final List<Character> effectOptions;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    private final Consumer<String> subtitleTextHandler;
    private final Consumer<Boolean> subtitlesEnabledHandler;
    private final SubtitleClient subtitleClient;
    private boolean subtitlesEnabled;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };
    private FrameProcessingCoordinator<ProcessedVideoFrame> processingCoordinator;

    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "video-decoder");
        thread.setDaemon(true);
        return thread;
    });
    private Future<DecodedVideoFrame> pendingDecodedFrame;
    private long decoderRequestGeneration = -1;
    private long lastPipelineLogNs;

    private record ProcessedVideoFrame(
            Frame frame,
            int frameNumber,
            long timestampUs,
            long logicalTimestampUs,
            long targetTimeNs) {
    }

    private record DecodedVideoFrame(
            Frame frame,
            int frameNumber,
            long timestampUs,
            long generation) {
    }

    private WorkerFrameProcessor workerFrameProcessor;
    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private boolean endOfStreamReached;
    // Identifies the currently valid playback/seek generation.
    // Older asynchronous results must not be displayed after a seek.
    private long playbackGeneration = 0;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    private long lastDisplayedFrameNs = 0;
    private long fpsWindowStartNs = 0;
    private int displayedFramesInWindow = 0;
    private long previousPulseNs = 0;
    private static final int STARTUP_READY_FRAMES = 10;
    private static final double SUBTITLE_DELAY_SECONDS = 0.2;
    private boolean startupBuffering;

    public VideoPlayerModel(
            boolean audioEnabled,
            boolean subtitlesEnabled,
            List<FrameProcessor> processors,
            List<Character> effectOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler,
            Consumer<String> subtitleTextHandler,
            Consumer<Boolean> subtitlesEnabledHandler) {
        System.out.println(
                "[DEBUG] video.workers property = "
                        + System.getProperty("video.workers"));
        System.out.println(
                "[CONFIG] Worker count: " + WORKER_COUNT);
        audioOutputEnabled = audioEnabled;
        this.subtitlesEnabled = subtitlesEnabled;
        this.subtitleTextHandler = subtitleTextHandler;
        this.subtitlesEnabledHandler = subtitlesEnabledHandler;
        this.subtitleClient = new SubtitleClient(
                message -> Platform.runLater(() -> notifyStatusChanged(message)),
                () -> Platform.runLater(this::refreshSubtitleText));
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>(processors);
        this.effectOptions = List.copyOf(effectOptions);
        // frameProcessors.add(new FrameBleeder());
        // frameProcessors.add(new FrameScratcher());
        // frameProcessors.add(new FrameDuster());
        // frameProcessors.add(new FramePepperer());
        // frameProcessors.add(new FrameBlackAndWhiter());
        // frameProcessors.add(new FrameYellower());
        // frameProcessors.add(new FrameVignetter());
        // frameProcessors.add(new FrameFlickerer());
        // frameProcessors.add(new FrameJitterer());
        // frameProcessors.add(new FrameNumberer());
        System.out.println("[CONFIG] Subtitles initially enabled: " + this.subtitlesEnabled);
    }

    public void play(File file) {
        videoFile = file;
        subtitleClient.setMedia(file);
        System.out.println("[SUBTITLE] Initial state: " + subtitlesEnabled);
        if (subtitlesEnabled) subtitleClient.requestNear(0);
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public void toggleSubtitles() {
        subtitlesEnabled = !subtitlesEnabled;

        System.out.println(
            "[SUBTITLE] Toggled to: " + subtitlesEnabled
        );

        subtitlesEnabledHandler.accept(subtitlesEnabled);

        if (subtitlesEnabled && videoFile != null) {
            subtitleClient.requestNear(
                currentTimestampUs / 1_000_000.0
            );
        }

        refreshSubtitleText();
    }

    public boolean isSubtitlesEnabled() {
        return subtitlesEnabled;
    }

    private void refreshSubtitleText() {
        double currentSeconds = currentTimestampUs / 1_000_000.0;

        double subtitleTime = Math.max(
                0,
                currentSeconds - SUBTITLE_DELAY_SECONDS
        );

        subtitleTextHandler.accept(
                subtitlesEnabled
                        ? subtitleClient.textAt(subtitleTime)
                        : ""
        );
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        subtitleTextHandler.accept("");
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        subtitleClient.close();
        decoderExecutor.shutdown();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void resetProcessingPipeline() {
        playbackGeneration++;
        if (processingCoordinator != null) {
            processingCoordinator.close();
        }

        processingCoordinator = new FrameProcessingCoordinator<>(
                WORKER_COUNT,
                MAX_IN_FLIGHT);

        workerFrameProcessor = null;
        frameProcessorsInitialised = false;
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            resetProcessingPipeline();

            pendingDecodedFrame = null;
            decoderRequestGeneration = -1;

            // The single decoder executor guarantees that any previous
            // decode finishes before FFmpeg changes its position.
            decoderExecutor.submit(() -> {
                grabber.setTimestamp(grabTimestampUs);
                return null;
            }).get();

            resetPlaybackClock(logicalTimestampUs);
            if (subtitlesEnabled) subtitleClient.requestNear(logicalTimestampUs / 1_000_000.0);
            refreshSubtitleText();
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());
        refreshSubtitleText();

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        long ffmpegStart = System.nanoTime();
        grabber.start();

        System.out.printf(
            "[STARTUP] FFmpeg initialization: %.2f ms%n",
            (System.nanoTime() - ffmpegStart) / 1_000_000.0
        );

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            long audioStart = System.nanoTime();

            audioPlayer.open(
                grabber.getSampleRate(),
                grabber.getAudioChannels()
            );

            System.out.printf(
                "[STARTUP] Audio initialization: %.2f ms%n",
                (System.nanoTime() - audioStart) / 1_000_000.0
            );
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
        processingCoordinator = new FrameProcessingCoordinator<>(
                WORKER_COUNT,
                MAX_IN_FLIGHT);
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        startupBuffering = true;
        previousPulseNs = 0;
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
        endOfStreamReached = false;
    }

    private long currentFrameTargetNs(long logicalTimestampUs) {
        long relativeTimestampUs = logicalTimestampUs - logicalPlaybackBaseUs;

        return playbackStartNs + relativeTimestampUs * 1_000L;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {
        if (!playbackOpen || pauseRequested || endOfStreamReached
                || processingCoordinator == null
                || processingCoordinator.pendingCount() >= MAX_IN_FLIGHT) {
            return;
        }

        try {
            readNextVideoFrame();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private PreparedFrame readNextVideoFrame() throws Exception {

        // Start decoding without blocking JavaFX.
        if (pendingDecodedFrame == null) {
            decoderRequestGeneration = playbackGeneration;
            long generation = decoderRequestGeneration;

            pendingDecodedFrame = decoderExecutor.submit(
                    () -> decodeOneFrame(generation));

            return null;
        }

        // Never block JavaFX waiting for the decoder.
        if (!pendingDecodedFrame.isDone()) {
            return null;
        }

        DecodedVideoFrame decoded = pendingDecodedFrame.get();
        pendingDecodedFrame = null;

        // Ignore frames belonging to an earlier playback generation.
        if (decoded.generation() != playbackGeneration) {
            return null;
        }

        Frame frame = decoded.frame();

        if (frame == null) {
            endOfStreamReached = true;
            return null;
        }

        long timestampUs = decoded.timestampUs();
        int frameNumber = decoded.frameNumber();

        if (audioAvailable && frame.samples != null) {
            queueAudio(timestampUs, frame);
        }

        if (frame.image == null) {
            // Audio-only frames do not need image processing.
            // Prefetch the next frame instead of wasting
            // another JavaFX timer callback.
            if (playbackOpen && !pauseRequested
                    && !endOfStreamReached) {
                long generation = playbackGeneration;

                pendingDecodedFrame = decoderExecutor.submit(
                        () -> decodeOneFrame(generation));
            }
            return null;
        }

        if (!frameProcessorsInitialised) {
            InfoVideo infoVideo = new InfoVideo(
                    mediaName(videoFile),
                    totalVideoFrames,
                    frame.imageWidth,
                    frame.imageHeight,
                    frame.imageDepth,
                    frame.imageChannels,
                    frame.imageStride,
                    frameRate,
                    intFrameRate,
                    videoFrameDurationUs,
                    grabber.getPixelFormat());

            workerFrameProcessor = new WorkerFrameProcessor(
                    effectOptions, infoVideo);

            frameProcessorsInitialised = true;
        }

        if (firstTimestampUs == NO_SEEK_REQUEST) {
            firstTimestampUs = timestampUs;
            playbackStartNs = System.nanoTime();

            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        }

        InfoFrame info = new InfoFrame(frameNumber, timestampUs);

        long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);

        long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;

        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

        WorkerFrameProcessor processorForTask = workerFrameProcessor;

        boolean submitted = processingCoordinator.submit(() -> {
            Frame processed = processorForTask.process(frame, info);

            return new ProcessedVideoFrame(
                    processed,
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs);
        });

        if (!submitted) {
            throw new IllegalStateException(
                    "Could not submit frame for processing");
        }

        // Start decoding the next frame immediately.
        // This allows decoding and worker processing to overlap.
        if (playbackOpen
                && !pauseRequested
                && !endOfStreamReached
                && pendingDecodedFrame == null) {

            long generation = playbackGeneration;

            pendingDecodedFrame = decoderExecutor.submit(
                    () -> decodeOneFrame(generation));
        }

        return null;
    }

    /**
     * Reads one media frame on the dedicated decoder thread.
     *
     * The FFmpeg grabber must not be accessed concurrently
     * by the JavaFX thread or another decoder task.
     */
    private DecodedVideoFrame decodeOneFrame(long generation)
            throws Exception {

        Frame frame = grabFrame();

        if (frame == null) {
            return new DecodedVideoFrame(
                    null,
                    -1,
                    NO_SEEK_REQUEST,
                    generation);
        }

        long timestampUs = grabber.getTimestamp();
        int frameNumber = grabber.getFrameNumber();

        // FFmpeg may reuse its internal buffers.
        // Return an independent copy to the consumer.
        Frame frameCopy = frame.clone();

        return new DecodedVideoFrame(
                frameCopy,
                frameNumber,
                timestampUs,
                generation);
    }

    private void collectProcessedFrame() {
        if (!playbackOpen || processingCoordinator == null
                || preparedFrame != null) {
            return;
        }

        try {
            var completed = processingCoordinator.pollNextReady();

            if (completed.isEmpty()) {
                return;
            }

            ProcessedVideoFrame result = completed.get();

            long convertStartNs = System.nanoTime();

            Image image = converter.convert(result.frame());

            double convertMs = (System.nanoTime() - convertStartNs) / 1_000_000.0;

            if (convertMs > 10.0) {
                System.out.printf("[CONVERT] %.2f ms%n", convertMs);
            }

            long relativeTimestampUs = result.logicalTimestampUs() - logicalPlaybackBaseUs;

            long adjustedTargetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            preparedFrame = new PreparedFrame(
                    image,
                    result.frameNumber(),
                    result.timestampUs(),
                    result.logicalTimestampUs(),
                    adjustedTargetTimeNs,
                    System.nanoTime());

        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative
    // here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return
    // control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1)
    // keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in
    // the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one
    // at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen || pauseRequested) {
            return;
        }
        if (previousPulseNs != 0) {
            double gapMs = (now - previousPulseNs) / 1_000_000.0;

            if (gapMs > 100) {
                System.out.printf(
                    "[PULSE] JavaFX pulse gap: %.1f ms%n",
                    gapMs
                );
            }
        }

        previousPulseNs = now;
        collectProcessedFrame();
        if (!playbackOpen) {
            return;
        }
        // Wait until the initial frame buffer is ready.
        // Do not start the playback clock too early.
        if (startupBuffering) {
            int readyFrames = (preparedFrame != null ? 1 : 0)
                    + processingCoordinator.readyInOrderCount();

            boolean enoughFrames =
                    preparedFrame != null
                    && (readyFrames >= STARTUP_READY_FRAMES
                        || (endOfStreamReached
                            && pendingDecodedFrame == null));

            if (!enoughFrames) {
                prepareNextFrame();
                return;
            }

            // Start the media clock only after buffering.
            playbackStartNs = now;
            startupBuffering = false;

            System.out.println(
                    "[STARTUP] Initial buffer ready: "
                    + readyFrames + " frames");
        }
        // Task (1)
        writeDueAudio(now);

        // Task (2)
        if (preparedFrame != null
                && currentFrameTargetNs(preparedFrame.logicalTimestampUs()) <= now) {
            displayPreparedFrame(now);
        }
        if (endOfStreamReached
                && preparedFrame == null
                && processingCoordinator != null
                && processingCoordinator.pendingCount() == 0
                && pendingAudio.isEmpty()
                && pendingDecodedFrame == null) {
            finishPlayback();
            return;
        }

        // Task (3). We'll try and be nice to the GUI event loop here by queueing the
        // prepareNextFrame call rather than
        // hogging the thread and doing it here, hence the tricky callback and use of
        // prepareNextFrameQueued. Feel free
        // to replace this whole block with simple linear control flow logic
        // such as: if (!pauseRequested && preparedFrame == null) prepareNextFrame();
        // to compare.
        if (!pauseRequested
                && !endOfStreamReached
                && processingCoordinator != null
                && processingCoordinator.pendingCount() < MAX_IN_FLIGHT) {
            prepareNextFrame();
        }
    }

    private void displayPreparedFrame(long now) {
        long displayNs = System.nanoTime();

        if (lastDisplayedFrameNs != 0) {
            double gapMs =
                    (displayNs - lastDisplayedFrameNs) / 1_000_000.0;

            if (gapMs > 200) {
                System.out.printf(
                        "[DISPLAY] Gap between frames: %.1f ms%n",
                        gapMs
                );
            }
        }

        lastDisplayedFrameNs = displayNs;

        if (fpsWindowStartNs == 0) {
            fpsWindowStartNs = displayNs;
        }

        displayedFramesInWindow++;

        double elapsedSeconds =
                (displayNs - fpsWindowStartNs) / 1_000_000_000.0;

        if (elapsedSeconds >= 1.0) {
            System.out.printf(
                    "[DISPLAY] Delivered FPS: %.1f%n",
                    displayedFramesInWindow / elapsedSeconds
            );

            fpsWindowStartNs = displayNs;
            displayedFramesInWindow = 0;
        }
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time
        // performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (currentFrameTargetNs(frame.logicalTimestampUs()) - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
        if (subtitlesEnabled) {
            subtitleClient.requestNear(currentTimestampUs / 1_000_000.0);
        }
        refreshSubtitleText();
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        subtitleTextHandler.accept("");
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        subtitleTextHandler.accept("");
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackGeneration++;
        if (processingCoordinator != null) {
            processingCoordinator.close();
            processingCoordinator = null;
        }

        workerFrameProcessor = null;
        playbackTimer.stop();
        playbackOpen = false;
        endOfStreamReached = false;
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        pendingDecodedFrame = null;
        decoderRequestGeneration = -1;

        if (grabber != null) {
            FFmpegFrameGrabber closingGrabber = grabber;
            grabber = null;

            try {
                decoderExecutor.submit(() -> {
                    try {
                        closingGrabber.stop();
                    } finally {
                        closingGrabber.close();
                    }
                    return null;
                }).get();
            } catch (Exception e) {
                System.err.println(
                        "Error closing decoder: " + e.getMessage());
            }
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private static void processFrame(
            Frame frame,
            InfoFrame info,
            List<FrameProcessor> processors) throws Exception {
        for (FrameProcessor processor : processors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable && audioOutputEnabled) {
            return grabber.grab();
        }

        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}