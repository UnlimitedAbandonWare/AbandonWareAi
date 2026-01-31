package com.example.lms.api.dto;

import com.example.lms.domain.ChatSession;



public class ChatSessionDto {
    private Long id;
    private String title;
    private String createdAt;

    public ChatSessionDto() {}

    public ChatSessionDto(Long id, String title, String createdAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
    }

    public static ChatSessionDto from(ChatSession s) {
        String ts = (s.getCreatedAt() != null) ? s.getCreatedAt().toString() : null;
        return new ChatSessionDto(s.getId(), s.getTitle(), ts);
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}