package com.example.lms.service;


/**
 * Compatibility alias for documentation diagrams that refer to
 * AbandonWareAi_ChatService. The actual implementation lives in ChatService.
 * <p>
 * This alias intentionally does **not** extend {@link ChatService} to avoid forcing
 * a Lombok-generated constructor chain during compilation. It exists solely to keep
 * docs/diagrams and grep-able references stable without adding runtime beans.
 */
final class AbandonWareAi_ChatService {
    private AbandonWareAi_ChatService() {
        // Prevent instantiation; class exists for aliasing only.
    }
}