package com.example.lms.audio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;



/**
 * REST controller exposing text-to-speech (TTS) and speech-to-text (STT) services.
 *
 * <p>This controller delegates synthesis and transcription to the respective
 * {@link OpenAiSpeechService} and {@link OpenAiTranscriptionService} beans.
 * When synthesising, the input text is converted to an MP3 byte array and
 * returned with an appropriate content type.  When transcribing, the
 * uploaded audio is forwarded to the transcription service and the
 * resulting text is returned.  Errors result in a 500 response with
 * an empty body.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audio")
public class AudioController {

    private final OpenAiSpeechService speech;
    private final OpenAiTranscriptionService stt;

    /**
     * Synthesize the provided text into speech.  The request body should be
     * plain text.  On success the method returns an MP3 payload with
     * content disposition set to inline.  On failure an empty array is
     * returned.
     *
     * @param text the text to synthesise
     * @return an MP3 byte array wrapped in a ResponseEntity
     */
    @PostMapping(value = "/tts", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> tts(@RequestBody String text) {
        byte[] mp3 = speech.synthesize(text == null ? "" : text);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech.mp3\"")
                .body(mp3);
    }

    /**
     * Transcribe the provided audio file into text.  The uploaded audio
     * should be sent as multipart/form-data.  On success the method returns
     * the transcription result; on failure an empty string and a 500 status
     * code are returned.
     *
     * @param file the uploaded audio file
     * @return the transcribed text or an empty string on error
     */
    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> stt(@RequestPart("file") MultipartFile file) {
        try {
            String text = stt.transcribe(file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(text);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("");
        }
    }
}