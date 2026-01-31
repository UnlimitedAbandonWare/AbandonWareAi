package com.example.lms.audio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;



/**
 * Service for performing speech-to-text (STT) transcriptions using the
 * OpenAI Whisper API.  This implementation leverages the dedicated
 * {@code openaiWebClient} configured with extended timeouts and a
 * large in-memory buffer to upload audio files as multipart form
 * requests.  The API key and base URL are resolved from configuration or
 * environment variables.
 */
@Service
public class OpenAiTranscriptionService {
    // Resolve the API key for OpenAI from configuration or environment variables.
    // Prefer the `openai.api.key` property and fall back to OPENAI_API_KEY.  Do
    // not fall back to other vendor keys such as GROQ_API_KEY to avoid
    // invalid credential mixing.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;
    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;
    @Value("${openai.stt.model:whisper-1}")
    private String model;

    private final WebClient openaiWebClient;

    public OpenAiTranscriptionService(@Qualifier("openaiWebClient") WebClient openaiWebClient) {
        this.openaiWebClient = openaiWebClient;
    }

    /**
     * Transcribe the given audio bytes into text.  The audio is sent as a
     * multipart/form-data request to the OpenAI whisper endpoint.  A
     * JSON response containing the transcription is returned.  When
     * unsuccessful an empty string is returned.
     *
     * @param filename filename to send for the audio part
     * @param wav the raw audio bytes
     * @return the transcribed text or an empty string on failure
     */
    public String transcribe(String filename, byte[] audio) {
        /*
         * Build a multipart request body for Whisper transcription.  The
         * content type of the uploaded audio is derived from the file
         * extension when available.  When the filename is null or
         * unrecognised the content type falls back to application/octet-stream.
         */
        var mbb = new org.springframework.http.client.MultipartBodyBuilder();
        MediaType ct = MediaType.APPLICATION_OCTET_STREAM;
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".webm")) {
            ct = MediaType.parseMediaType("audio/webm");
        } else if (lower.endsWith(".wav")) {
            ct = MediaType.parseMediaType("audio/wav");
        } else if (lower.endsWith(".mp3")) {
            ct = MediaType.parseMediaType("audio/mpeg");
        }
        mbb.part("file", audio)
                .filename(filename)
                .contentType(ct);
        mbb.part("model", model);
        // Uncomment the following line to hint Whisper to transcribe Korean
        // mbb.part("language", "ko");
        try {
            String json = openaiWebClient.post()
                    .uri(baseUrl + "/v1/audio/transcriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(mbb.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (json == null || json.isBlank()) {
                return "";
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return node.path("text").asText("");
        } catch (Exception e) {
            // Log the failure instead of silently swallowing it.  Returning an empty
            // string signals the caller that transcription failed without
            // propagating the exception.  The log level is set to warn
            // because repeated failures could indicate a configuration problem.
            org.slf4j.LoggerFactory.getLogger(OpenAiTranscriptionService.class).warn("STT failed: {}", e.toString());
            return "";
        }
    }
}