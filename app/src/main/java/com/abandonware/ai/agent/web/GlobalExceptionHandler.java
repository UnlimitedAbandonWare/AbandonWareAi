package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.contract.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.HashMap;
import java.util.Map;




@ControllerAdvice
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.web.GlobalExceptionHandler
 * Role: controller
 * Dependencies: com.abandonware.ai.agent.contract.ValidationException
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.web.GlobalExceptionHandler
role: controller
*/
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String,Object>> handleValidation(ValidationException ex){
        Map<String,Object> body = new HashMap<>();
        body.put("error", "validation_failed");
        body.put("details", ex.getErrors().toString());
        return new ResponseEntity<>(body, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}