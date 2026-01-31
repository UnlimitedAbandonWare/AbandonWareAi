package com.example.lms.audio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;



/**
 * Service for performing text-to-speech (TTS) synthesis using the
 * OpenAI speech API.  This service utilises the dedicated
 * {@code openaiWebClient} to send JSON payloads specifying the model,
 * voice and input text.  The response is returned as a byte array
 * representing an MP3 file.  When an error occurs an empty byte array
 * is returned.
 */
@Service
public class OpenAiSpeechService {
    // Resolve the API key for OpenAI from configuration or environment variables.
    // Prefer the `openai.api.key` property and fall back to OPENAI_API_KEY only.
    // Never fall back to other vendor keys (e.g. GROQ) to avoid invalid credentials.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;
    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;
    // Default to a 5-series TTS model; update to gemma3:27b-tts to align with chat defaults
    @Value("${openai.tts.model:gemma3:27b-tts}")
    private String model;
    @Value("${openai.tts.voice:alloy}")
    private String voice;
    private final WebClient openaiWebClient;

    public OpenAiSpeechService(@Qualifier("openaiWebClient") WebClient openaiWebClient) {
        this.openaiWebClient = openaiWebClient;
    }

    /**
     * Synthesize the given text into speech.  Returns the raw audio bytes
     * encoded as MP3.  When unsuccessful an empty array is returned.
     *
     * @param text the text to synthesise
     * @return the MP3 bytes or an empty array on failure
     */
    public byte[] synthesize(String text) {
        try {
            // Build a simple JSON payload using ObjectMapper to properly escape input
            String payload = String.format(
                    "{\"model\":\"%s\",\"voice\":\"%s\",\"input\":%s,\"format\":\"mp3\"}",
                    model,
                    voice,
                    new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(text).toString()
            );
            return openaiWebClient.post()
                    .uri(baseUrl + "/v1/audio/speech")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            // Log the failure and return an empty array.  This prevents an
            // exception from bubbling up through the controller while still
            // surfacing the error in the logs for debugging.  The log level
            // is warn to attract attention without spamming at error.
            org.slf4j.LoggerFactory.getLogger(OpenAiSpeechService.class).warn("TTS failed: {}", e.toString());
            return new byte[0];
        }
    }
}