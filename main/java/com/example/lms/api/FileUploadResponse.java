package com.example.lms.api;


// 레코드를 사용해 불변의 간단한 데이터 객체를 만듭니다.
public record FileUploadResponse(String message, String fileUrl, boolean success) {
}