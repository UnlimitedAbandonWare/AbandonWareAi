package com.example.lms.dto;

import lombok.Data;



@Data
public class MessageFormDto {
    private String targetId;
    private String message;
    private String url;

    public String getUserKey() {
        return targetId;
    }

    public void setUserKey(String userKey) {
        this.targetId = userKey;
    }
}
