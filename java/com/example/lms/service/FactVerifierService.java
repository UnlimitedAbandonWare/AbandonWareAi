package com.example.lms.service;
import com.example.lms.domain.enums.SourceCredibility;                // ★ 추가
import com.example.lms.service.verification.SourceAnalyzerService;    // ★ 추가
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
/* 🔴 기타 import 유지 */
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.service.rag.guard.MemoryAsEvidenceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Arrays;
@Slf4j
@Service
public class FactVerifierService {
    private final SourceAnalyzerService sourceAnalyzer;   // ★ 신규 의존성
    private final OpenAiService openAi;
    private final FactStatusClassifier classifier;
    private final com.example.lms.service.verification.ClaimVerifierService claimVerifier; // + NEW
    private final EvidenceGate evidenceGate;              //  NEW
    private final MemoryAsEvidenceAdapter memAdapter;     //  NEW
    // 개체 추출기(선택 주입). 없으면 기존 정규식 폴백.
    @Autowired(required = false)
    private com.example.lms.service.ner.NamedEntityExtractor entityExtractor;

    public FactVerifierService(OpenAiService openAi,
                               FactStatusClassifier classifier,
                               SourceAnalyzerService sourceAnalyzer,
                               com.example.lms.service.verification.ClaimVerifierService claimVerifier,
                               EvidenceGate evidenceGate,
                               MemoryAsEvidenceAdapter memAdapter) {
        // <<<<<<<<<<<< 여기에 코드를 삽입
        this.openAi = Objects.requireNonNull(openAi, "openAi");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.sourceAnalyzer = Objects.requireNonNull(sourceAnalyzer, "sourceAnalyzer");
        this.claimVerifier = Objects.requireNonNull(claimVerifier, "claimVerifier");
        this.evidenceGate = Objects.requireNonNull(evidenceGate, "evidenceGate");
        this.memAdapter = Objects.requireNonNull(memAdapter, "memAdapter");
    }


    private static final int MIN_CONTEXT_CHARS = 80;
    /** 컨텍스트가 질문과 같은 ‘도메인/개체’인지 먼저 점검하는 메타 단계 */
    private static final String META_TEMPLATE = """
            You are a meta fact-checker.
            Decide if the CONTEXT can safely answer the QUESTION without hallucination.
            Output exactly one of: CONSISTENT | MISMATCH | INSUFFICIENT
            and a one-sentence reason (in Korean).

            QUESTION:
            %s

            CONTEXT:
            %s
            """;

    private static final String TEMPLATE = """
            You are a senior investigative journalist and fact‑checker.

            ## TASK
            1. Read the **Question**, **Context**, and **Draft answer** below.
            2. Compare the Draft with the Context (Context has higher authority).
            3. A fact is verified only if **at least two independent Context lines** state the same information.
            4. Remove or explicitly mark any named entities (characters/items/regions) that **do not appear in Context**.
                        4-1. For any **pairing/synergy** claims (e.g., "A works well with B"):
                            - Treat as VERIFIED only if Context contains an explicit synergy cue
                              (e.g., "잘 어울린다", "시너지", "조합", "함께 쓰면 좋다") relating A↔B.
                            - Mere **stat comparisons**, **co-mentions**, or **example lists** are NOT sufficient.
            5. If the Draft is fully consistent, reply exactly:
               STATUS: PASS
               CONTENT:
               <copy the draft verbatim>
            6. If the Draft contains factual errors or misses key info, fix it **concisely** (max 20%% longer) and reply:
               STATUS: CORRECTED
               CONTENT:
               <your revised answer in Korean>
            7. If the Context is insufficient to verify, reply:
               STATUS: INSUFFICIENT
               CONTENT:
               <copy the draft verbatim>

            ## QUESTION
            %s

            ## CONTEXT
            %s

            ## DRAFT
            %s
            """;

    /** 하위호환: 기존 시그니처는 memory=null로 위임 */
    public String verify(String question, String context, String draft, String model) {
        return verify(question, context, /*memory*/ null, draft, model);
    }

    /** 메모리 증거를 별도 인자로 받아 EvidenceGate에 반영 */
    public String verify(String question,
                         String context,
                         String memory,
                         String draft,
                         String model) {
        if (!StringUtils.hasText(draft)) return "";
        // ✅ 컨텍스트가 빈약해도 즉시 "정보 없음"으로 보내지 않고 SOFT-FAIL 경로 유지
        boolean hasCtx = StringUtils.hasText(context) && context.length() >= MIN_CONTEXT_CHARS;
        boolean hasMem = StringUtils.hasText(memory) && memory.length() >= 40; // 간이 컷
        if (!hasCtx && !hasMem) {
            var res = claimVerifier.verifyClaims("", draft, model);
            return res.verifiedAnswer();
        }

        if (StringUtils.hasText(context) && context.contains("[검색 결과 없음]")) return draft;
        // ── 0) META‑CHECK: 컨텍스트가 아예 다른 대상을 가리키는지(또는 부족한지) 1차 판별 ──

        // ★ 0) 소스 신뢰도 메타 점검: 팬 추측/상충이면 즉시 차단
        try {
            SourceCredibility cred = sourceAnalyzer.analyze(question, context);
            if (cred == SourceCredibility.FAN_MADE_SPECULATION
                    || cred == SourceCredibility.CONFLICTING) {
                log.warn("[Meta-Verify] 낮은 신뢰도({}) 탐지 → 답변 차단", cred);
                return "웹에서 찾은 정보는 공식 발표가 아닌 팬 커뮤니티의 추측일 가능성이 높습니다. "
               + "이에 기반한 답변은 부정확할 수 있어 제공하지 않습니다.";
            }
        } catch (Exception e) {
            log.debug("[Meta-Verify] source analysis 실패: {}", e.toString());
        }
        try {
            String metaPrompt = String.format(META_TEMPLATE, question, context);
            ChatCompletionRequest metaReq = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), metaPrompt)))
                    .temperature(0d)
                    .topP(0.05d)
                    .build();
            String metaRaw = openAi.createChatCompletion(metaReq)
                    .getChoices().get(0).getMessage().getContent();
            String verdict = (metaRaw == null ? "" : metaRaw).trim().toUpperCase();
            if (verdict.startsWith("MISMATCH")) {
                // 예: 질문은 ‘게임 캐릭터’, 컨텍스트는 ‘요리사’일 때
                log.debug("[Verify] META‑CHECK=MISMATCH → 정보 없음");
                return "정보 없음";
            }
            // INSUFFICIENT은 아래 일반 검증 단계로 이어서 처리
        } catch (Exception e) {
            log.debug("[Verify] META‑CHECK failed: {}", e.toString());
        }


        FactVerificationStatus status = classifier.classify(question, context, draft, model);
        // LLM 기반 개체 추출 사용(없으면 정규식 폴백)
        var entities = (entityExtractor != null) ? entityExtractor.extract(draft) : extractEntities(draft);
        boolean grounded = groundedInContext(context, entities, 2);

        // ✅ 증거 게이트: 메모리/KB 포함(후속질의 완화는 상위에서 isFollowUp 전달 시 확장)
        boolean enoughEvidence = evidenceGate.hasSufficientCoverage(
                question,
                toLines(context),   // RAG snippets (List<String>)
                context,            // RAG unified context (String)
                toLines(memory),    // Memory snippets (List<String>)
                List.of(),          // KB snippets (없으면 빈 리스트)
                /*followUp*/ false
        );

        switch (status) {
            case PASS, INSUFFICIENT -> {
                // ✅ 엄격 차단 대신: 근거 부족이면 SOFT‑FAIL 필터링 후 출력
                if (!grounded || !enoughEvidence) {
                    log.debug("[Verify] grounding/evidence 부족 → SOFT-FAIL 필터만 적용");
                    var res = claimVerifier.verifyClaims(mergeCtx(context, memory), draft, model);
                    return res.verifiedAnswer().isBlank() ? "정보 없음" : res.verifiedAnswer();
                }
                // PASS여도 조합/시너지 등 unsupported claim 제거
                var res = claimVerifier.verifyClaims(mergeCtx(context, memory), draft, model);
                return res.verifiedAnswer();
            }
            case CORRECTED -> {
                if (!grounded || !enoughEvidence) {
                    log.debug("[Verify] CORRECTED도 grounding/evidence 부족 → SOFT-FAIL 필터 후 출력");
                    var res = claimVerifier.verifyClaims(mergeCtx(context, memory), draft, model);
                    return res.verifiedAnswer().isBlank() ? "정보 없음" : res.verifiedAnswer();
                }
                String gPrompt = String.format(TEMPLATE, question, context, draft);
                ChatCompletionRequest req = ChatCompletionRequest.builder()
                        .model(model)
                        .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), gPrompt)))
                        .temperature(0d)
                        .topP(0.05d)
                        .build();
                try {
                    String raw = openAi.createChatCompletion(req)
                            .getChoices().get(0).getMessage().getContent();
                    int split = raw.indexOf("CONTENT:");
                    String corrected = (split > -1 ? raw.substring(split + 8).trim() : raw.trim());
                    var res = claimVerifier.verifyClaims(mergeCtx(context, memory), corrected, model);
                    return res.verifiedAnswer();
                } catch (Exception e) {
                    log.error("Correction generation failed – fallback to '정보 없음'", e);
                    return "정보 없음";
                }
            }
            default -> {
                return draft;
            }
        }
    }

    private static String mergeCtx(String ctx, String mem) {
        String c = (ctx == null ? "" : ctx);
        String m = (mem == null || mem.isBlank()) ? "" : ("\n\n### LONG-TERM MEMORY\n" + mem);
        return c + m;
    }

    /** 간단 개체 추출(LLM 추출기 없을 시 폴백용) */
    private static List<String> extractEntities(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] patterns = {
                "(?i)\\b(Core\\s+Ultra\\s+\\d+\\s*\\d*[A-Z]?)\\b",
                "(?i)\\b(Ryzen\\s+[3579]\\s+\\d{3,5}[A-Z]?)\\b",
                "(?i)\\b(Arc\\s+Graphics)\\b",
                "(?i)\\b([A-Z]{1,3}\\d{1,4}[A-Z]?)\\b",
                "(?i)(코어\\s*울트라\\s*\\d+\\s*\\d*[A-Z]?)",
                "(?i)(라데온|인텔|AMD)",
                // + 게임 고유명사 예시
                "(?i)(다이루크|후리나|푸리나|원신|genshin|에스코피에|escoffier)"
        };
        for (String p : patterns) {
            var m = Pattern.compile(p).matcher(text);
            while (m.find()) {
                String e = m.group(0).trim();
                if (e.length() > 1 && !out.contains(e)) out.add(e);
            }
        }
        return out;
    }

    /** util: split into non-empty lines */
    private static List<String> toLines(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split("\\R+"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }

    /** 개체가 서로 다른 컨텍스트 라인에 최소 minLines 등장하는지 */
    private static boolean groundedInContext(String context, List<String> entities, int minLines) {
        if (context == null || context.isBlank() || entities == null || entities.isEmpty()) return false;
        String[] lines = context.split("\\R+");
        int ok = 0;
        for (String e : entities) {
            int c = 0;
            for (String ln : lines) {
                if (ln.toLowerCase().contains(e.toLowerCase())) c++;
                if (c >= minLines) break;
            }
            if (c >= minLines) ok++;
        }
        return ok > 0;
    }
}
