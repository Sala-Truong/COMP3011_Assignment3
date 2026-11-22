/*
 * COMP3011 Assignment 2
 * Author: Le Bao Uyen Truong a1909745
 *
 * Student implementation for concurrent video frame processing.
 */

package comp3011;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs independent frame-processing tasks concurrently and makes
 * their results available in submission order.
 *
 * Task submission and result retrieval are owned by one thread,
 * normally the JavaFX application thread.
 *
 * Worker tasks must not access JavaFX UI objects, the shared
 * FFmpegFrameGrabber, or mutable frames owned by other tasks.
 */
public final class FrameProcessingCoordinator<T> implements AutoCloseable {

    private final ExecutorService workers;
    private final Queue<Future<T>> pending = new ArrayDeque<>();
    private final int maxInFlight;

    private boolean closed;

    public FrameProcessingCoordinator(int workerCount, int maxInFlight) {
        if (workerCount < 1 || maxInFlight < workerCount) {
            throw new IllegalArgumentException(
                    "Invalid worker count or queue capacity");
        }

        this.maxInFlight = maxInFlight;
        this.workers = Executors.newFixedThreadPool(workerCount);
    }

    /**
     * Submit a task without blocking the caller.
     * Returns false when at capacity or already closed.
     */
    public boolean submit(Callable<T> task) {
        Objects.requireNonNull(task);

        if (closed || pending.size() >= maxInFlight) {
            return false;
        }

        pending.add(workers.submit(task));
        return true;
    }

    /**
     * Retrieve the oldest submitted result if it has finished.
     * Never waits for unfinished work.
     */
    public Optional<T> pollNextReady() {
        Future<T> oldest = pending.peek();

        if (oldest == null || !oldest.isDone()) {
            return Optional.empty();
        }

        pending.remove();

        try {
            return Optional.of(
                    Objects.requireNonNull(oldest.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        } catch (CancellationException e) {
            throw new CompletionException(e);
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Cancel queued/in-progress work and stop accepting tasks.
     * Does not wait for slow or uninterruptible worker tasks.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        for (Future<T> task : pending) {
            task.cancel(true);
        }

        pending.clear();
        workers.shutdownNow();
    }
}