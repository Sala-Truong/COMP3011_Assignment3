/*
 * COMP3011 Assignment 2
 * Author: Le Bao Uyen Truong a1909745
 *
 * Student implementation for concurrent video frame processing.
 */

package comp3011;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FrameProcessingCoordinatorTest {

    @Test
    void preservesSubmissionOrder() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        try (FrameProcessingCoordinator<Integer> coordinator = new FrameProcessingCoordinator<>(2, 4)) {

            assertTrue(coordinator.submit(() -> {
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out");
                }
                return 1;
            }));

            assertTrue(coordinator.submit(() -> {
                secondFinished.countDown();
                return 2;
            }));

            assertTrue(secondFinished.await(5, TimeUnit.SECONDS));

            // Frame 2 finished, but frame 1 must be returned first.
            assertEquals(Optional.empty(), coordinator.pollNextReady());

            releaseFirst.countDown();

            assertEquals(1, awaitNext(coordinator));
            assertEquals(2, awaitNext(coordinator));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void respectsMaximumPendingCapacity() throws Exception {
        CountDownLatch release = new CountDownLatch(1);

        try (FrameProcessingCoordinator<Integer> coordinator = new FrameProcessingCoordinator<>(2, 2)) {

            assertTrue(coordinator.submit(() -> {
                release.await();
                return 1;
            }));

            assertTrue(coordinator.submit(() -> {
                release.await();
                return 2;
            }));

            // The coordinator must reject additional tasks.
            assertFalse(coordinator.submit(() -> 3));

            assertEquals(2, coordinator.pendingCount());

            release.countDown();

            assertEquals(1, awaitNext(coordinator));
            assertEquals(2, awaitNext(coordinator));

            assertEquals(0, coordinator.pendingCount());
            assertTrue(coordinator.submit(() -> 4));
            assertEquals(4, awaitNext(coordinator));
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsTasksAfterClosing() {
        FrameProcessingCoordinator<Integer> coordinator = new FrameProcessingCoordinator<>(2, 4);

        coordinator.close();

        assertFalse(coordinator.submit(() -> 10));
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void returnsEmptyWhenNoTasksExist() {
        try (FrameProcessingCoordinator<Integer> coordinator = new FrameProcessingCoordinator<>(2, 4)) {

            assertEquals(Optional.empty(), coordinator.pollNextReady());
        }
    }

    private static int awaitNext(
            FrameProcessingCoordinator<Integer> coordinator) throws Exception {

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            Optional<Integer> result = coordinator.pollNextReady();

            if (result.isPresent()) {
                return result.get();
            }

            Thread.sleep(5);
        }

        fail("Timed out waiting for completed task");
        return -1;
    }
}