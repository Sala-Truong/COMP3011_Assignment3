# COMP3011 Assignment 2 — Concurrent Video Player

## Overview

This project extends the supplied JavaFX video player with configurable visual effects and concurrent frame processing.

The implementation allows effects to be selected through command-line arguments while preserving their specified order, including repeated effects.

The concurrent architecture uses a fixed worker-thread pool to process independent video frames while keeping playback coordination and JavaFX rendering separate from effect processing.

## Build and Run

Requirements:
- Java compatible with the project's `pom.xml`
- Maven
- A video file supported by FFmpeg

Build and test:

```bash
mvn clean verify
```

Display command-line help:

```bash
mvn javafx:run '-Djavafx.args=--help'
```

Run with effects:

```bash
mvn javafx:run '-Djavafx.args=-jfddn /path/to/video.mp4'
```

Effects are applied in command-line order and may be repeated.

## Concurrency Architecture

### FrameProcessingCoordinator

The coordinator manages a fixed thread pool using Java's `ExecutorService`.

Frames are submitted as independent processing tasks. The coordinator keeps track of pending results using `Future` objects in submission order, allowing completed results to be retrieved in the correct playback sequence.

The number of outstanding frames is bounded to prevent unlimited task accumulation and memory consumption.

### WorkerFrameProcessor

Each worker thread maintains its own list of frame processors using `ThreadLocal`.

Processor instances are not shared between workers. This isolates effect-specific mutable state and prevents concurrent workers from modifying the same effect instance.

Effects are applied sequentially within each frame, respecting the order specified on the command line.

### VideoPlayerModel

The model coordinates media decoding, task submission, retrieval of processed frames, audio scheduling, and playback state.

Video frames are cloned before being submitted for concurrent processing so that workers operate on independent frame data rather than decoder-owned buffers.

Processed frames are retrieved in submission order to preserve video-frame ordering.

### Synchronisation and Correctness

The implementation uses the following concurrency principles:

- **Frame ownership:** Each submitted task receives its own frame data.
- **Processor isolation:** Worker-specific processor pipelines avoid sharing mutable effect state.
- **Ordered results:** Pending futures are retrieved in submission order.
- **Bounded concurrency:** The coordinator limits the number of outstanding tasks.
- **Thread lifecycle:** Worker tasks are cancelled or shut down when playback resources are closed.
- **JavaFX thread safety:** GUI rendering remains separate from background effect-processing tasks.

These design choices reduce the risk of data races, out-of-order rendering, and uncontrolled resource usage.

## Testing

Automated tests are provided under `src/test/java/comp3011`.

### CommandLineControllerTest

Tests command-line parsing, effect options, invalid arguments, and related CLI behaviour.

### FrameProcessingCoordinatorTest

Tests frame-result ordering, task capacity, and coordinator shutdown behaviour.

Run all configured tests using:

```bash
mvn clean verify
```

The latest local verification completed with 16 tests passing and no failures or errors.

## Performance Evaluation

A synthetic concurrency benchmark is available in `FrameProcessingBenchmark.java`.

In a local benchmark using 60 independent simulated tasks:

| Configuration | Average duration |
|---|---:|
| Single worker | 2029.58 ms |
| Multiple workers | 236.12 ms |
| Synthetic speedup | 8.60× |

These results demonstrate concurrent scheduling for the benchmark workload. They do not establish an equivalent improvement for CPU-intensive video effects.

A separate real-video test using the `-jfddn` effect sequence recorded approximately 2.5 ms of effect-processing time per frame with both one and nine workers. This workload was not sufficiently demanding to establish a significant real-video performance improvement.

## Authorship

Original starter application: Simon Ratcliffe, with the attribution retained in the supplied source files.

Student modifications and concurrent implementation: Le Bao Uyen Truong - a1909745.