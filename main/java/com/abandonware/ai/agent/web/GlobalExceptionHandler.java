
package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.contract.ValidationException;
import com.example.lms.trace.SafeRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.HashMap;
import java.util.Map;




@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String,Object>> handleValidation(ValidationException ex){
        Map<String,Object> body = new HashMap<>();
        body.put("error", "validation_failed");
        body.put("details", SafeRedactor.safeMessage(String.valueOf(ex.getErrors()), 240));
        body.put("detailsHash", SafeRedactor.hashValue(String.valueOf(ex.getErrors())));
        body.put("detailCount", ex.getErrors().size());
        return new ResponseEntity<>(body, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
