package com.example.lms.dto;


/**
 * 간단한 첨부 파일 메타 정보 DTO.
 *
 * 업로드된 파일의 ID, 파일명, 크기, 타입 및 접근 URL을 클라이언트에 전달합니다.
 */
public record AttachmentDto(
    String id,
    String name,
    long size,
    String contentType,
    String url
) {}