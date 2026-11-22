/*
 * COMP3011 Assignment 2
 * Author: Le Bao Uyen Truong a1909745
 *
 * Student implementation for concurrent video frame processing.
 */

package comp3011;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class FrameProcessingBenchmark {

    private static final int TASK_COUNT = 60;
    private static final int WORK_MS = 30;
    private static final int RUNS = 3;

    private FrameProcessingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int parallelWorkers = Math.max(
                2, Runtime.getRuntime().availableProcessors() - 1);

        System.out.println("Frame Processing Benchmark");
        System.out.println("Tasks: " + TASK_COUNT);
        System.out.println("Work per task: " + WORK_MS + " ms");

        // Warm-up both configurations.
        runBenchmark(1);
        runBenchmark(parallelWorkers);

        double serialTotal = 0;
        double parallelTotal = 0;

        for (int run = 1; run <= RUNS; run++) {
            double serialMs = runBenchmark(1);
            double parallelMs = runBenchmark(parallelWorkers);

            serialTotal += serialMs;
            parallelTotal += parallelMs;

            System.out.printf(
                    "Run %d | Serial: %.2f ms | Parallel: %.2f ms%n",
                    run, serialMs, parallelMs);
        }

        double serialAverage = serialTotal / RUNS;
        double parallelAverage = parallelTotal / RUNS;

        System.out.printf("%nAverage serial: %.2f ms%n",
                serialAverage);
        System.out.printf("Average parallel: %.2f ms%n",
                parallelAverage);
        System.out.printf("Speedup: %.2fx%n",
                serialAverage / parallelAverage);
    }

    private static double runBenchmark(int workers)
            throws Exception {

        int capacity = workers * 2;
        int submitted = 0;
        int completed = 0;

        long start = System.nanoTime();

        try (FrameProcessingCoordinator<Integer> coordinator = new FrameProcessingCoordinator<>(
                workers, capacity)) {

            while (completed < TASK_COUNT) {

                while (submitted < TASK_COUNT
                        && coordinator.pendingCount() < capacity) {

                    final int taskId = submitted;

                    boolean accepted = coordinator.submit(() -> {
                        Thread.sleep(WORK_MS);
                        return taskId;
                    });

                    if (!accepted) {
                        throw new IllegalStateException(
                                "Unexpected submission failure");
                    }

                    submitted++;
                }

                Optional<Integer> result = coordinator.pollNextReady();

                if (result.isPresent()) {
                    if (result.get() != completed) {
                        throw new IllegalStateException(
                                "Incorrect result order");
                    }
                    completed++;
                } else {
                    Thread.sleep(1);
                }
            }
        }

        return (System.nanoTime() - start)
                / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }
}