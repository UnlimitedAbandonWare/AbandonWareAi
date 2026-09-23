package com.example.lms.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ChatApiController.class)
final class SelectionReplayExceptionHandler {

    record ErrorBody(String code) {
    }

    @ExceptionHandler(SelectionReplayRequestException.class)
    ResponseEntity<ErrorBody> handle(SelectionReplayRequestException failure) {
        return ResponseEntity.status(failure.status()).body(new ErrorBody(failure.code()));
    }
}
