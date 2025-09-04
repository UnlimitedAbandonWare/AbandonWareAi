package com.example.lms.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@ControllerAdvice
public class GlobalExceptionAdvice {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handle(Exception e) {
        String id = UUID.randomUUID().toString();
        log.warn("[ERR:{}] {}", id, e.toString());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", e.getClass().getSimpleName());
        body.put("message", e.getMessage());
        body.put("ts", Instant.now().toString());
        body.put("traceId", id);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
