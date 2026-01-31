package com.example.lms.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;



public class GoogleTranslateDto {

    @Data
    @AllArgsConstructor
    public static class TranslateRequest {
        @JsonProperty("sourceLanguageCode")
        private String sourceLang;

        @JsonProperty("targetLanguageCode")
        private String targetLang;

        @JsonProperty("contents")
        private List<String> contents;
    }

    @Data
    public static class TranslateResponse {
        @Data
        public static class Translation {
            @JsonProperty("translatedText")
            private String translatedText;
        }
        @JsonProperty("translations")
        private List<Translation> translations;
    }
}