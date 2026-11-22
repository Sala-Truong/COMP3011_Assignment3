/*
 * COMP3011 Assignment 2
 * Author: Le Bao Uyen Truong a1909745
 *
 * Student implementation for concurrent video frame processing.
 */

package comp3011;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.bytedeco.javacv.Frame;

/**
 * Processes frames using a separate effect pipeline for each
 * worker thread.
 *
 * Each pipeline is created and initialised on first use.
 * The same worker reuses its pipeline for subsequent frames.
 */
public final class WorkerFrameProcessor {

    private final List<Character> effectOptions;
    private final InfoVideo videoInfo;

    private final ThreadLocal<List<FrameProcessor>> workerProcessors = new ThreadLocal<>();

    public WorkerFrameProcessor(
            List<Character> effectOptions,
            InfoVideo videoInfo) {

        this.effectOptions = List.copyOf(
                Objects.requireNonNull(effectOptions));
        this.videoInfo = Objects.requireNonNull(videoInfo);
    }

    /**
     * Applies all configured effects in command-line order.
     *
     * Must be called by an effect-processing worker, with
     * a frame that is exclusively owned by that task.
     */
    public Frame process(Frame frame, InfoFrame info) throws Exception {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(info);

        List<FrameProcessor> processors = workerProcessors.get();

        if (processors == null) {
            processors = FrameProcessorFactory.createProcessors(
                    effectOptions);

            for (FrameProcessor processor : processors) {
                processor.initialise(videoInfo);
            }

            workerProcessors.set(processors);
        }

        for (FrameProcessor processor : processors) {
            processor.process(frame, info);
        }

        return frame;
    }
}