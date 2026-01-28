package com.abandonware.ai.example.lms.cfvm;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;




@ControllerAdvice
@Order(1) // early
@RequiredArgsConstructor
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.example.lms.cfvm.CfvmExceptionAdvice
 * Role: controller
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.example.lms.cfvm.CfvmExceptionAdvice
role: controller
*/
public class CfvmExceptionAdvice {

    private final CfvmRawService cfvm;
    private final CfvmRawProperties props;
    private final RawSlotExtractor extractor = new BuildLogSlotExtractor(); // pluggable

    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public void onAnyError(Throwable ex) {
        if (!props.isEnabled()) return;
        String sid = CfvmRawService.currentSessionIdOr("global");
        List<RawSlot> slots = extractor.extract(ex, RawSlot.Stage.RUNTIME, sid);
        if (slots.isEmpty()) {
            // Fallback generic slot
            cfvm.push(RawSlot.builder()
                    .sessionId(sid)
                    .stage(RawSlot.Stage.RUNTIME)
                    .code(ex.getClass().getSimpleName())
                    .path(ex.getStackTrace().length>0 ? ex.getStackTrace()[0].getClassName() : "unknown")
                    .message(String.valueOf(ex.getMessage()))
                    .tags(Map.of())
                    .ts(Instant.now())
                    .build());
        } else {
            slots.forEach(cfvm::push);
        }
        // Allow default Spring error handling to proceed (no ResponseEntity here)
    }
}