package com.example.lms.dto.help;


/** 프런트(chat.js)가 content 또는 message 중 하나를 사용하므로 둘 다 제공합니다. */
public class ContextHelpResponse {
        private String content;
        private String message;

        public ContextHelpResponse() {}
        public ContextHelpResponse(String text) {
                this.content = text;
                this.message = text;
        }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
}