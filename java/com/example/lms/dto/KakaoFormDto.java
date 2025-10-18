package com.example.lms.dto;

import lombok.Data;



@Data
public class KakaoFormDto {
    private String userKey;
    private String message;
    private String url;
}