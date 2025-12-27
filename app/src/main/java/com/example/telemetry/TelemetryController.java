package com.example.telemetry;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/internal/telemetry")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.telemetry.TelemetryController
 * Role: controller
 * Key Endpoints: GET /internal/telemetry/stream, ANY /internal/telemetry/internal/telemetry
 * Feature Flags: telemetry, sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.telemetry.TelemetryController
role: controller
api:
  - GET /internal/telemetry/stream
  - ANY /internal/telemetry/internal/telemetry
flags: [telemetry, sse]
*/
public class TelemetryController {
  private final LoggingSseEventPublisher sse;
  public TelemetryController(LoggingSseEventPublisher sse){ this.sse = sse; }

  @GetMapping(path="/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(){ return sse.createEmitter(); }
}