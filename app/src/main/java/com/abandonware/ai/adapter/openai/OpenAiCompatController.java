package com.abandonware.ai.adapter.openai;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private final RestTemplate rest;
    private final Environment env;

    @Autowired
    public OpenAiCompatController(Environment env) {
        this.rest = new RestTemplate();
        this.env = env;
    }

    // --- DTOs ---
    public static class ChatMessage {
        public String role;
        public String content;
    }
    public static class ChatRequest {
        public String model;
        public List<ChatMessage> messages;
        public Integer max_tokens;
        public Double temperature;
        public Boolean stream;
        public List<String> stop;
    }
    public static class ChoiceDelta {
        public ChatMessage delta = new ChatMessage();
        public String finish_reason;
        public int index = 0;
    }
    public static class Choice {
        public ChatMessage message;
        public String finish_reason;
        public int index = 0;
    }
    public static class Usage {
        public int prompt_tokens;
        public int completion_tokens;
        public int total_tokens;
    }
    public static class ChatResponse {
        public String id;
        public String object = "chat.completion";
        public long created = Instant.now().getEpochSecond();
        public String model;
        public List<Choice> choices = new ArrayList<>();
        public Usage usage = new Usage();
    }
    public static class ModelsResponse {
        public String object = "list";
        public List<Map<String, Object>> data = new ArrayList<>();
    }

    @GetMapping("/models")
    public ModelsResponse models() {
        ModelsResponse res = new ModelsResponse();
        Map<String, Object> m = new HashMap<>();
        m.put("id", Optional.ofNullable(env.getProperty("llm.model-id")).orElse("local-llama"));
        m.put("object", "model");
        res.data.add(m);
        return res;
    }

    @PostMapping(path="/chat/completions", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> completions(@RequestBody ChatRequest req) {
        boolean stream = Boolean.TRUE.equals(req.stream);
        if (stream) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream(req));
        }
        String prompt = extractPrompt(req);
        String generated = generateViaLoopback(prompt);
        var resp = toOpenAiStyleResponse(req, generated);
        return ResponseEntity.ok(resp);
    }

    // --- Streaming via SSE (rudimentary word-split streaming) ---
    private SseEmitter stream(ChatRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                String prompt = extractPrompt(req);
                String text = generateViaLoopback(prompt);
                String[] tokens = text.split("(?<=\\s)|(?=\\s)"); // preserve spaces
                for (String t : tokens) {
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("id", UUID.randomUUID().toString());
                    chunk.put("object", "chat.completion.chunk");
                    chunk.put("created", Instant.now().getEpochSecond());
                    chunk.put("model", Optional.ofNullable(req.model).orElse("local-llama"));
                    Map<String, Object> choice = new LinkedHashMap<>();
                    Map<String, Object> delta = new LinkedHashMap<>();
                    delta.put("content", t);
                    choice.put("delta", delta);
                    choice.put("index", 0);
                    choice.put("finish_reason", null);
                    chunk.put("choices", Collections.singletonList(choice));
                    emitter.send(SseEmitter.event().name("message").data(chunk));
                }
                // end signal
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("id", UUID.randomUUID().toString());
                done.put("object", "chat.completion.chunk");
                done.put("created", Instant.now().getEpochSecond());
                done.put("model", Optional.ofNullable(req.model).orElse("local-llama"));
                Map<String, Object> choice = new LinkedHashMap<>();
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("content", "");
                choice.put("delta", delta);
                choice.put("index", 0);
                choice.put("finish_reason", "stop");
                done.put("choices", Collections.singletonList(choice));
                emitter.send(SseEmitter.event().name("message").data(done));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // --- Helpers ---
    private String extractPrompt(ChatRequest req) {
        if (req == null || req.messages == null || req.messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : req.messages) {
            if (m == null || m.content == null) continue;
            // naive role tagging (system ignored for now)
            if (!"system".equalsIgnoreCase(m.role)) {
                sb.append(m.content).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String generateViaLoopback(String prompt) {
        // fallback: call existing local endpoint /api/llm/generate
        try {
            String url = Optional.ofNullable(env.getProperty("openai.compat.loopback-url"))
                    .orElse("http://localhost:8080/api/llm/generate");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", prompt);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            Map resp = rest.postForObject(url, entity, Map.class);
            if (resp != null && resp.containsKey("text")) {
                return String.valueOf(resp.get("text"));
            }
            // fallback textualization
            return String.valueOf(resp);
        } catch (Exception e) {
            return "[local-llm error] " + e.getMessage();
        }
    }

    private ChatResponse toOpenAiStyleResponse(ChatRequest req, String text) {
        var r = new ChatResponse();
        r.id = UUID.randomUUID().toString();
        r.model = Optional.ofNullable(req.model).orElse("local-llama");
        Choice c = new Choice();
        ChatMessage msg = new ChatMessage();
        msg.role = "assistant";
        msg.content = text;
        c.message = msg;
        c.finish_reason = "stop";
        r.choices.add(c);
        int promptTokens = Math.max(1, extractPrompt(req).length() / 4); // naive
        int completionTokens = Math.max(1, text.length() / 4);
        r.usage.prompt_tokens = promptTokens;
        r.usage.completion_tokens = completionTokens;
        r.usage.total_tokens = promptTokens + completionTokens;
        return r;
    }
}