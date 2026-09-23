package com.example.lms.dto.agent;

public record ScoreEventDto(
        String rule,
        int redDelta,
        int greenDelta,
        int blueDelta) {
}
