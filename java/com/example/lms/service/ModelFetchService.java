// src/main/java/com/example/lms/service/ModelFetchService.java
package com.example.lms.service;


import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.example.lms.entity.ModelEntity;
import com.example.lms.model.ModelInfo;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.repository.ModelInfoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * OpenAI /v1/models 목록을 주기적으로 동기화하여
 *   1) 상세 메타(ModelInfo)
 *   2) 드롭다운용 요약(ModelEntity)
 * 두 테이블을 모두 **업서트(upsert) 방식**으로 갱신한다.
 */
@EnableScheduling
@Service
@RequiredArgsConstructor
public class ModelFetchService {
    private static final AtomicBoolean updating = new AtomicBoolean(false);

    private static final Logger log = LoggerFactory.getLogger(ModelFetchService.class);

    /** application.yml 의 openai.api.key 값을 주입.  When unset this falls
     *  back to the OPENAI_API_KEY environment variable.  Do not fall back
     *  to other vendor keys (e.g. GROQ_API_KEY) to prevent using
     *  incompatible credentials. */
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;

    /** Base URL for the OpenAI-compatible endpoint (should end with /v1). */
    @Value("${openai.api.url:https://api.openai.com/v1}")
    private String openaiApiUrl;

    // Extracts a Windows drive path from Ollama's OpenAI-compat error body:
    // e.g. "mkdir E:\\models: The system cannot find the path specified"
    private static final Pattern OLLAMA_MKDIR_WIN_PATH =
            Pattern.compile("mkdir\\s+([A-Za-z]:\\\\[^:\\r\\n\\\"]+)");

    private final ModelInfoRepository   modelInfoRepo;
    private final ModelEntityRepository modelEntityRepo;

    private final RestTemplate rest = new RestTemplate();

    /**
     * 애플리케이션 기동 직후 1회 + 매시 정각마다 실행
     */
    @PostConstruct
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void updateModelList() {
        if (!updating.compareAndSet(false, true)) { return; }
        try {

        String base = (openaiApiUrl == null || openaiApiUrl.isBlank())
                ? "https://api.openai.com/v1"
                : openaiApiUrl.trim();
        base = base.replaceAll("/+$", "");
        String modelsUrl = base.endsWith("/models") ? base : (base + "/models");

        boolean isLocal = isLocalHost(modelsUrl);

        // Remote(OpenAI) requires an API key; local OpenAI-compatible servers often don't.
        if (!isLocal && (openaiApiKey == null || openaiApiKey.isBlank())) {
            log.error("[ModelFetch] OpenAI API Key 미설정 - application.yml 의 'openai.api.key' 확인 필요");
            return;
        }

        try {
            /* ----------------------------------------------------------------
             * 1) OpenAI /v1/models 호출
             * ---------------------------------------------------------------- */
            HttpHeaders headers = new HttpHeaders();
            if (openaiApiKey != null && !openaiApiKey.isBlank()) {
                headers.setBearerAuth(openaiApiKey);
            }
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<JsonNode> res;
            try {
                res = rest.exchange(
                        modelsUrl,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        JsonNode.class);
            } catch (HttpStatusCodeException ex) {
                // Common local Ollama failure: model store directory missing.
                // Attempt a safe best-effort auto-heal (Windows only, localhost only).
                String body = safeBody(ex);
                boolean healed = tryHealOllamaMkdir(modelsUrl, body);
                if (healed) {
                    try {
                        res = rest.exchange(
                                modelsUrl,
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                JsonNode.class);
                    } catch (Exception retryEx) {
                        log.warn("[ModelFetch] 모델 목록 동기화 실패 (retry after heal): {}", retryEx.toString());
                        return;
                    }
                } else {
                    // Local failures are noisy but should not break boot.
                    if (isLocal) {
                        log.warn("[ModelFetch] Local /v1/models 실패 ({}): {}", ex.getStatusCode().value(), trimForLog(body));
                    } else {
                        log.error("[ModelFetch] Remote /v1/models 실패 ({}): {}", ex.getStatusCode().value(), trimForLog(body));
                    }
                    return;
                }
            } catch (ResourceAccessException ex) {
                // Local server down / connection refused
                if (isLocal) {
                    log.warn("[ModelFetch] Local LLM endpoint unreachable: {}", ex.getMessage());
                } else {
                    log.error("[ModelFetch] Remote LLM endpoint unreachable: {}", ex.getMessage());
                }
                return;
            }

            JsonNode data = res.getBody().path("data");
            if (!data.isArray()) {
                log.warn("[ModelFetch] 예상치 못한 응답 형식: {}", data);
                return;
            }

            /* ----------------------------------------------------------------
             * 2) 기존 레코드를 한 번에 가져와 Map 으로 보관
             *    → select 난사 방지 & JPA saveAll() 한 방에!
             * ---------------------------------------------------------------- */
            Map<String, ModelInfo>   existingInfoMap = modelInfoRepo.findAll().stream()
                    .collect(Collectors.toMap(ModelInfo::getModelId, Function.identity()));

            Map<String, ModelEntity> existingEntityMap = modelEntityRepo.findAll().stream()
                    .collect(Collectors.toMap(ModelEntity::getModelId, Function.identity()));

            List<ModelInfo>   infosToSave    = new ArrayList<>();
            List<ModelEntity> entitiesToSave = new ArrayList<>();

            /* ----------------------------------------------------------------
             * 3) JSON → Entity 변환 (owner 기본값 처리) + 리스트 수집
             * ---------------------------------------------------------------- */
            for (JsonNode m : data) {
                String id       = m.path("id").asText();
                String objType  = m.path("object").asText();
                long   created  = m.path("created").asLong();
                String ownedBy  = m.path("owned_by").asText(null);

                // owner 필드는 NOT NULL 제약 - 비어 있으면 기본값 openai
                if (ownedBy == null || ownedBy.isBlank()) {
                    ownedBy = "local";
                }

                /* -------- ModelInfo (상세) -------- */
                ModelInfo info = existingInfoMap.getOrDefault(id, new ModelInfo());
                info.setModelId(id);
                info.setObjectType(objType);
                info.setCreated(created);
                info.setOwnedBy((ownedBy) == null || (ownedBy).isBlank() ? "local" : (ownedBy));
                infosToSave.add(info);

                /* -------- ModelEntity (드롭다운용 요약) -------- */
                ModelEntity entity = existingEntityMap.getOrDefault(id, new ModelEntity());
                entity.setModelId(id);
                entity.setOwner(ownedBy);
                entitiesToSave.add(entity);
            }

            /* ----------------------------------------------------------------
             * 4) 한 방에 업서트
             * ---------------------------------------------------------------- */
            modelInfoRepo.saveAll(infosToSave);
            modelEntityRepo.saveAll(entitiesToSave);

            log.info("[ModelFetch] 동기화 완료 - {}개 모델 저장/업데이트", entitiesToSave.size());

        } catch (Exception ex) {
            // 트랜잭션이 rollback 되며 예외가 전파됨
            log.error("[ModelFetch] 모델 목록 동기화 실패", ex);
        }
        } finally { updating.set(false); }
    }

    private static boolean isLocalHost(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null) return false;
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWindows() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("win");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryHealOllamaMkdir(String modelsUrl, String errorBody) {
        if (!isWindows()) return false;
        if (!isLocalHost(modelsUrl)) return false;
        if (errorBody == null || errorBody.isBlank()) return false;

        Matcher m = OLLAMA_MKDIR_WIN_PATH.matcher(errorBody);
        if (!m.find()) {
            return false;
        }
        String p = m.group(1);
        if (p == null || p.isBlank()) return false;
        // Defensive: only allow absolute drive paths like C:\\...
        if (!p.matches("^[A-Za-z]:\\\\.*")) {
            return false;
        }

        try {
            Path dir = Paths.get(p);
            Files.createDirectories(dir);
            log.warn("[ModelFetch] Ollama models dir missing. Created directory: {}", dir);
            return true;
        } catch (Exception e) {
            log.warn("[ModelFetch] Failed to create Ollama models dir '{}': {}", p, e.toString());
            return false;
        }
    }

    private static String safeBody(HttpStatusCodeException ex) {
        try {
            String s = ex.getResponseBodyAsString();
            return (s == null) ? "" : s;
        } catch (Exception ignore) {
            return "";
        }
    }

    private static String trimForLog(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= 400) return t;
        return t.substring(0, 400) + "…";
    }

    /** 컨트롤러에서 호출: 저장된 모델 전부 반환 */
    public List<ModelEntity> getAllModels() {
        return modelEntityRepo.findAll();
    }

}