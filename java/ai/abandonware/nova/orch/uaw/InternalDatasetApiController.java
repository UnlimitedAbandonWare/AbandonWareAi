package ai.abandonware.nova.orch.uaw;

import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 내부용 데이터셋 적재 API (Nova Overlay).
 *
 * - POST /internal/dataset/rag
 * - uaw.dataset-api.enabled=true 일 때만 노출됩니다(오토컨피그에서 @Bean으로 등록).
 * - uaw.dataset-api.key 값이 설정되어 있으면, 헤더 X-Internal-Key가 일치해야 합니다.
 */
@Slf4j
@RestController
@RequestMapping("/internal/dataset")
public class InternalDatasetApiController {

    private final UawDatasetWriter writer;
    private final UawAutolearnProperties autolearnProps;
    private final String requiredKey;

    public InternalDatasetApiController(UawDatasetWriter writer, UawAutolearnProperties autolearnProps, Environment env) {
        this.writer = writer;
        this.autolearnProps = autolearnProps;
        this.requiredKey = env.getProperty("uaw.dataset-api.key", "");
    }

    @PostMapping("/rag")
    public ResponseEntity<Map<String, Object>> appendRag(
            @RequestBody AppendRagRequest req,
            @RequestHeader(value = "X-Internal-Key", required = false) String key
    ) {
        if (requiredKey != null && !requiredKey.isBlank()) {
            if (key == null || !requiredKey.equals(key)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "error", "unauthorized",
                        "message", "Missing/invalid X-Internal-Key"
                ));
            }
        }

        if (req == null || isBlank(req.getQuestion()) || isBlank(req.getAnswer())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "message", "question/answer are required"
            ));
        }

        String datasetName = firstNonBlank(
                req.getDatasetName(),
                (autolearnProps != null && autolearnProps.getDataset() != null) ? autolearnProps.getDataset().getName() : null,
                "manual"
        );

        String datasetPath = firstNonBlank(
                req.getDatasetPath(),
                (autolearnProps != null && autolearnProps.getDataset() != null) ? autolearnProps.getDataset().getPath() : null,
                "data/train_rag.jsonl"
        );

        int evidenceCount = (req.getEvidenceCount() != null)
                ? req.getEvidenceCount()
                : (autolearnProps != null ? autolearnProps.getMinEvidenceCount() : 0);
        if (evidenceCount < 0) {
            evidenceCount = 0;
        }

        String model = firstNonBlank(req.getModel(), "internal-api");
        String sessionId = firstNonBlank(req.getSessionId(), "internal");

        File file = new File(datasetPath);
        File parent = file.getParentFile();
        if (parent != null) {
            // Best-effort: ensure directory exists.
            parent.mkdirs();
        }

        // UawDatasetWriter signature:
        // append(File file, String datasetName, String question, String answer, String modelUsed, int evidenceCount, String sessionId)
        writer.append(
                file,
                datasetName,
                req.getQuestion().trim(),
                req.getAnswer().trim(),
                model,
                evidenceCount,
                sessionId
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "appended");
        resp.put("datasetName", datasetName);
        resp.put("datasetPath", datasetPath);
        resp.put("evidenceCount", evidenceCount);
        resp.put("model", model);
        resp.put("sessionId", sessionId);
        return ResponseEntity.ok(resp);
    }

    @Data
    public static class AppendRagRequest {
        private String question;
        private String answer;
        private Integer evidenceCount;
        private String model;
        private String datasetName;
        private String datasetPath;
        private String sessionId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
