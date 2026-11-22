package comp3011;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fetches timestamped subtitles without blocking video decoding or the UI.
 *
 * Each request sends a short clip as a WAV file to our local Spring Boot API.
 * It uses a SEPARATE FFmpeg decoder so the playback decoder remains untouched.
 * This class only stores captions; JavaFX draws them in VideoPlayerView.
 *
 * AI assistance: An initial implementation was drafted with ChatGPT. Review
 * all code and test network failures before submitting.
 */
public final class SubtitleClient implements AutoCloseable {
    private static final double CHUNK_SECONDS = 20.0;
    private static final String ENDPOINT = "http://localhost:8080/api/v1/subtitles";
    private static final long RETRY_DELAY_NS = TimeUnit.SECONDS.toNanos(6);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "subtitle-network-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Consumer<String> statusListener;
    private final Runnable updatedListener;
    private final AtomicLong generation = new AtomicLong();
    private final Map<Integer, List<SubtitleCue>> cuesByChunk = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastAttemptNs = new ConcurrentHashMap<>();
    private final Map<Integer, Long> requestsPending = new ConcurrentHashMap<>();
    private volatile File media;
    private volatile boolean closed;

    public SubtitleClient(Consumer<String> statusListener, Runnable updatedListener) {
        this.statusListener = statusListener;
        this.updatedListener = updatedListener;
    }

    /** Called on the JavaFX thread when a new media file is opened. */
    public void setMedia(File file) {
        if (file == null || file.equals(media)) return;
        generation.incrementAndGet();
        cuesByChunk.clear();
        lastAttemptNs.clear();
        requestsPending.clear();
        media = file;
    }

    /** Request the current chunk (and the next one shortly before its boundary). */
    public void requestNear(double seconds) {
        File current = media;
        if (current == null || closed || seconds < 0) return;
        int index = Math.max(0, (int) (seconds / CHUNK_SECONDS));
        requestChunk(current, index);
        if (seconds % CHUNK_SECONDS >= 12) requestChunk(current, index + 1);
    }

    /** Look up text using the MEDIA clock, not the wall-clock or API response time. */
    public String textAt(double seconds) {
        int index = Math.max(0, (int) (seconds / CHUNK_SECONDS));
        for (int candidate = Math.max(0, index - 1); candidate <= index; candidate++) {
            List<SubtitleCue> cues = cuesByChunk.get(candidate);
            if (cues == null) continue;
            for (SubtitleCue cue : cues) {
                if (cue.visibleAt(seconds)) return cue.text();
            }
        }
        return "";
    }

    private void requestChunk(File current, int index) {
        if (cuesByChunk.containsKey(index) || requestsPending.containsKey(index)) return;
        long now = System.nanoTime();
        Long last = lastAttemptNs.get(index);
        if (last != null && now - last < RETRY_DELAY_NS) return;
        lastAttemptNs.put(index, now);
        long jobGeneration = generation.get();
        requestsPending.put(index, jobGeneration);
        worker.submit(() -> {
            try {
                if (jobGeneration != generation.get()) return;
                long extractionStart = System.nanoTime();
                Chunk chunk = extractAudio(
                        current,
                        index * CHUNK_SECONDS,
                        CHUNK_SECONDS
                );

                double extractionMs =
                        (System.nanoTime() - extractionStart) / 1_000_000.0;

                System.out.printf(
                        "[SUBTITLE] Chunk %d audio extraction: %.2f ms%n",
                        index,
                        extractionMs
                );
                if (chunk == null || chunk.wav().length == 0) {
                    if (jobGeneration == generation.get()) cuesByChunk.put(index, List.of());
                    return;
                }
                List<SubtitleCue> cues = callServer(chunk);
                if (jobGeneration == generation.get()) {
                    cuesByChunk.put(index, cues);
                    updatedListener.run();
                }
            } catch (Exception e) {
                // Subtitles are optional: never crash or stop media playback.
                if (jobGeneration == generation.get()) {
                    statusListener.accept("Subtitles unavailable: " + e.getMessage());
                }
            } finally {
                requestsPending.remove(index, jobGeneration);
            }
        });
    }

    private record Chunk(byte[] wav, double audioStartSeconds) { }

    /** Decode audio from the original video without touching the playback grabber. */
    private Chunk extractAudio(File file, double startSeconds, double seconds) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();
            int rate = grabber.getSampleRate();
            int channels = grabber.getAudioChannels();
            if (rate <= 0 || channels <= 0) return null;
            if (startSeconds > 0) grabber.setTimestamp((long) (startSeconds * 1_000_000));

            AudioPlayer pcmConverter = new AudioPlayer();
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            long stopUs = (long) ((startSeconds + seconds) * 1_000_000);
            double audioStart = -1;
            Frame samples;
            while (!Thread.currentThread().isInterrupted()
                    && (samples = grabber.grabSamples()) != null) {
                long timestampUs = grabber.getTimestamp();
                if (timestampUs >= stopUs) break;
                if (timestampUs < (long) (startSeconds * 1_000_000)) continue;
                if (audioStart < 0) audioStart = timestampUs / 1_000_000.0;
                byte[] bytes = pcmConverter.copySamples(samples);
                audio.write(bytes);
            }
            byte[] pcm = audio.toByteArray();
            if (pcm.length == 0) return null;
            AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
            ByteArrayOutputStream wav = new ByteArrayOutputStream();
            try (AudioInputStream stream = new AudioInputStream(
                    new ByteArrayInputStream(pcm), format,
                    pcm.length / (2L * channels))) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav);
            }
            return new Chunk(wav.toByteArray(), audioStart);
        }
    }

    private List<SubtitleCue> callServer(Chunk chunk) throws Exception {
        String boundary = "----COMP3011-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(chunk.wav());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(80))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Subtitle server returned HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        JsonNode segments = root.path("segments");
        if (!segments.isArray()) throw new IOException("Invalid subtitle response");
        List<SubtitleCue> results = new ArrayList<>();
        for (JsonNode segment : segments) {
            double start = segment.path("start").asDouble() + chunk.audioStartSeconds();
            double end = segment.path("end").asDouble() + chunk.audioStartSeconds();
            String text = segment.path("text").asText("");
            results.addAll(SubtitleFormatter.format(start, end, text));
        }
        return List.copyOf(results);
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
