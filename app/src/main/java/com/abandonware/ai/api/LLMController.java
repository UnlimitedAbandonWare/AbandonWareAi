package com.abandonware.ai.api;

import com.abandonware.ai.service.ai.LocalLLMService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/llm")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.api.LLMController
 * Role: controller
 * Key Endpoints: POST /api/llm/generate, ANY /api/llm/api/llm
 * Dependencies: com.abandonware.ai.service.ai.LocalLLMService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.api.LLMController
role: controller
api:
  - POST /api/llm/generate
  - ANY /api/llm/api/llm
*/
public class LLMController {

    private final LocalLLMService llm;

    public LLMController(LocalLLMService llm) {
        this.llm = llm;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Prompt prompt) {
        try {
            String text = llm.generateText(prompt.prompt());
            return ResponseEntity.ok(new Result(llm.engineName(), text));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    public record Prompt(String prompt) {}
    public record Result(String engine, String text) {}
}