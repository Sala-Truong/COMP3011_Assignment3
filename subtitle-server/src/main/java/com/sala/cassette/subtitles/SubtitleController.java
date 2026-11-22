package com.sala.cassette.subtitles;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** New Assignment 3 routes, independent of Assignment 1's recording routes. */
@RestController
public class SubtitleController {
    private final SubtitleTranscriptionService service;

    public SubtitleController(SubtitleTranscriptionService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SubtitleTranscriptionService.SubtitleResult subtitles(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty audio file");
        }
        try {
            return service.transcribe(file);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transcription failed", e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "API key not configured", e);
        }
    }

    @GetMapping("/api/v1/global/usage")
    public UsageResponse usage() {
        return new UsageResponse(service.getUsageSeconds());
    }

    public record UsageResponse(double usage) { }
}
