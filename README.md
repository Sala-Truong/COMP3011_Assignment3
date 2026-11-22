# COMP3011 Assignment 3 — Working Draft

This is **not yet a verified final submission**. It reuses the uploaded Assignment 2 player and Assignment 1 backend and adds an initial subtitle implementation. Test it locally before submission.

## Components

- `video-player/`: the Assignment 2 JavaFX application, with a `-S` / `--subtitle-frames` switch, a CC button to the right of the audio button, the S hotkey, timestamp-based caption display, and a background HTTP/audio-extraction worker. Subtitles are currently drawn as a JavaFX overlay, not directly into the JavaCV frame buffer.
- `subtitle-server/`: original Spring Boot application plus `/api/v1/subtitles` and `/api/v1/global/usage`. The new endpoint sends WAV audio to OpenAI using `whisper-1`, `verbose_json`, both `word` and `segment` timestamp granularities, filters low-confidence silence, counts actual transcription seconds, and caches identical clip data in memory.

## Requirements

- JDK 25, Maven 3.9+, Internet connection.
- An `OPENAI_API_KEY` available to the server process only. **Never put the key into source code, screenshots, ZIP files, or Git.**
- A short MP4 video with spoken dialogue for testing (the player extracts audio via its bundled JavaCV/FFmpeg dependency).

## Run (two terminals)

Terminal A (server):

```sh
cd subtitle-server
export OPENAI_API_KEY='your-private-key-from-approved-source'
./mvnw spring-boot:run
```

Terminal B (player):

```sh
cd video-player
mvn clean test
mvn javafx:run '-Djavafx.args=-aS /absolute/path/to/video.mp4'
```

Check usage seconds:

```sh
curl http://localhost:8080/api/v1/global/usage
```

## What to test before submission

1. With `-S`, captions appear after audio is transcribed; S and CC toggle them without pausing video. With no `-S`, subtitles start off but can be enabled later.
2. Seek forward/back while subtitles are on. Try pause, mute, resizing and combined Assignment 2 effects.
3. Stop the backend during playback. Video should continue; captions resume after restoring the server (new attempts can occur after a 6-second cooldown).
4. Try another video from a USB drive and a media disconnection. Watch the console for errors.
5. Call `/api/v1/global/usage` before and after transcription and replay to check that cache hits do not increment usage.
6. Confirm the new Spring endpoints on a fresh launch, and retain output logs from `mvn clean test` for the viva.

## Known limitations — must address before claiming full HD compliance

- Captions are a **JavaFX overlay**, not a true reorderable frame processor; `-S` is parsed independently of the Assignment 2 effect list. This makes toggling responsive and preserves playback, but does **not** yet meet strict frame-effect ordering when `-S` is interleaved with other effects.
- Chunked audio extraction is intentionally simple. A very slow API call can delay requests after seeking; alignment across chunk boundaries and natural punctuation-based line breaks may need refinement.
- Cache entries are held in server memory for the current run, not persisted across server restarts. Concurrent identical requests may both call the API.
- The uploaded A2 implementation itself may have performance/resilience issues (e.g., a blocking decoder wait on seeking) that these changes do not fully fix.
- This workspace had Java 21 and no Maven, so full Maven build and end-to-end API/video tests **could not be performed here**. Standalone checks for CLI parsing and caption wrapping were passed.

## Explain this in the viva

**Why another thread?** Network requests and audio extraction can take seconds; running them on JavaFX's UI thread would freeze playback. We use a dedicated single-worker executor, and publish immutable caption lists to a thread-safe map.

**How is text synchronised?** Each subtitle is given an absolute media start/end time. On each presented frame, the player reads its media timestamp and finds the matching subtitle, independent of when the network request completed.

**Why a separate audio decoder?** Seeking and decoding the playback source already involves one dedicated decoder thread. A second temporary decoder reads an audio-only snippet without moving that playback decoder's position.

**What happens if the network fails?** The network worker catches the error, and playback continues. Previously downloaded captions remain available, and the client retries unseen chunks later.

**Why cache?** The server hashes clip bytes; identical audio can reuse a previous transcription result, reducing cloud usage and latency. A cache hit does not add to the seconds counter.

**Acknowledgement:** Original Assignment 2 starter code and user implementation are retained. ChatGPT assisted with the new Assignment 3 initial draft; students must inspect, adapt and understand the code and acknowledge AI use as required by the course.
