package com.sala.cassette.subtitles;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * Transcribes a short WAV clip and caches the timestamped result by audio content.
 * The OpenAI key stays on the server and is never sent to the video player.
 *
 * AI assistance: Initial implementation drafted with ChatGPT; reviewed/adapted
 * for the COMP3011 assignment (student must review and understand before submission).
 */
@Service
public class SubtitleTranscriptionService {
    private final RestClient client;
    private final String apiKey;
    private final ConcurrentHashMap<String, SubtitleResult> cache = new ConcurrentHashMap<>();
    private final DoubleAdder secondsTranscribed = new DoubleAdder();

    public SubtitleTranscriptionService(@Value("${openai.api.key:}") String apiKey) {
        this.apiKey = apiKey;
        this.client = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .build();
    }

    public SubtitleResult transcribe(MultipartFile audio) throws IOException {
        if (audio.isEmpty()) {
            throw new IllegalArgumentException("Audio file is empty");
        }
        byte[] bytes = audio.getBytes();
        String hash = sha256(bytes);
        SubtitleResult cached = cache.get(hash);
        if (cached != null) {
            return cached;
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is missing");
        }

        // Request both timestamp types. Whisper's response already has phrase
        // boundaries in 'segments', which the client uses for the display.
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("model", "whisper-1");
        form.add("response_format", "verbose_json");
        form.add("timestamp_granularities[]", "word");
        form.add("timestamp_granularities[]", "segment");
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "clip.wav";
            }
        });

        WhisperResponse response = client.post()
                .uri("/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(WhisperResponse.class);

        if (response == null) {
            throw new IOException("No transcription response received");
        }
        List<SubtitleSegment> visible = (response.segments() == null
                ? List.<WhisperSegment>of() : response.segments()).stream()
                .filter(s -> !(s.no_speech_prob() > 0.6 && s.avg_logprob() < -1.0))
                .filter(s -> s.text() != null && !s.text().isBlank())
                .map(s -> new SubtitleSegment(s.start(), s.end(), s.text().trim()))
                .toList();
        List<SubtitleWord> words = response.words() == null
                ? List.of()
                : response.words().stream()
                    .map(w -> new SubtitleWord(w.start(), w.end(), w.word()))
                    .toList();

        double duration = response.duration() == null ? 0.0 : response.duration();
        if (duration <= 0) {
            duration = visible.stream().mapToDouble(SubtitleSegment::end).max().orElse(0);
        }
        SubtitleResult result = new SubtitleResult(visible, words);
        // Only actual API calls add usage; cache hits do not.
        secondsTranscribed.add(Math.max(0.0, duration));
        cache.putIfAbsent(hash, result);
        return result;
    }

    public double getUsageSeconds() {
        return secondsTranscribed.sum();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public record SubtitleResult(List<SubtitleSegment> segments, List<SubtitleWord> words) { }
    public record SubtitleSegment(double start, double end, String text) { }
    public record SubtitleWord(double start, double end, String word) { }

    // Snake_case field names match Whisper's verbose_json fields directly.
    public record WhisperResponse(Double duration, List<WhisperSegment> segments,
                                  List<WhisperWord> words) { }
    public record WhisperSegment(double start, double end, String text,
                                 double avg_logprob, double no_speech_prob) { }
    public record WhisperWord(double start, double end, String word) { }
}
