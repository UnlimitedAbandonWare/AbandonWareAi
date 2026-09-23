package com.example.lms.service.fallback;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.List;
import com.example.lms.agent.KnowledgeGapLogger;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




// Knowledge gap logging and domain/subject resolution
@Service
@RequiredArgsConstructor
public class SmartFallbackService {
    private static final Logger log = LoggerFactory.getLogger(SmartFallbackService.class);

    /**
     * Lazily provide a ChatModel for fallback generation.  We use an
     * ObjectProvider so that the fallback service does not eagerly
     * initialize an expensive LLM client on startup.  When no ChatModel
     * is available, the service falls back to a template-based response.
     */
    private final ObjectProvider<ChatModel> chatModelProvider;

    // Knowledge gap logging dependencies.  These are optional and will be null if the corresponding
    // beans are not defined in the Spring context.  They allow the fallback service to record
    // failed queries for autonomous exploration.
    private final com.example.lms.agent.KnowledgeGapLogger gapLogger;
    private final com.example.lms.service.knowledge.KnowledgeBaseService knowledgeBase;
    private final com.example.lms.service.subject.SubjectResolver subjectResolver;
    private final PromptBuilder promptBuilder;

    @Value("${fallback.enabled:true}")
    private boolean enabled;
    @Value("${fallback.model:${llm.chat-model:gemma4:26b}}")
    private String model;
    @Value("${fallback.temperature:0.2}")
    private double temperature;
    @Value("${fallback.top-p:0.7}")
    private double topP;
    @Value("${fallback.max-tokens:280}")
    private Integer maxTokens;

    /**
     * ?뚢뫂???쎈뱜揶쎛 ?봔鈺곌퉲釉?쳞怨뺢돌 筌ㅼ뮇伊??????'?類ｋ궖 ??곸벉'????
     * ????????/??쎈퉸???類ㅼ㉦???대Ŋ???랁?????됱뱽 ??뽯뻻??롫뮉 ??媛????????밴쉐??뺣뼄.
     *
     * @return null ??????媛?沃섎챷???     */
    public @Nullable String maybeSuggest(String userQuery, @Nullable String joinedContext, @Nullable String verified) {
        if (!enabled) return null;

        boolean insufficient = !StringUtils.hasText(joinedContext);
        boolean noInfo = StringUtils.hasText(verified) && "?類ｋ궖 ??곸벉".equals(verified.trim());
        if (!insufficient && !noInfo) return null;

        FallbackHeuristics.Detection det = FallbackHeuristics.detect(userQuery);
        if (det == null) return null; // ?袁⑥컭???얜챷???? 筌뤿굟???? ??? 野껋럩????λ뮞

        List<String> candidates = FallbackHeuristics.suggestAlternatives(det.domain(), det.wrongTerm());

        ChatModel llm = chatModelProvider.getIfAvailable();
        if (llm == null) return templateFallback(det, candidates, userQuery); // ??됱읈 ??쀫탣??
        String system = """
                ??덈뮉 ?뚢뫂???쎈뱜揶쎛 ?봔鈺곌퉲釉??怨뱀넺?癒?퐣 ???????롫즲???類ㅼ㉦??獄쏅뗀以??볥뮉 ??볥럢????곷뻻??쎄쉘?紐껊뼄.
                - ??μ젟??? 筌띾Þ?? 筌ㅼ뮆? 6餓???沅→에????릭??
                - ??덉쨮???????'??μ젟'??? 筌띾뜄?? ??뽯툧?? "揶쎛?館釉??袁⑤궖"嚥???쀬겱??롮뵬.
                - ?? 燁살뮇??揶쏄쑨猿???쇱뒠.
                ?類ㅻ뻼:
                1) ?얜챷???? ?얜똻毓?紐? 1?얜챷????곗뺘?????) + ????野껊슣?ユ??얜떯???                2) "揶쎛?館釉??袁⑤궖:" 筌뤴뫖以??곗쨮 2~3揶???뽯뻻(筌욁룂? ??곸?)
                3) 筌띾뜆?筌?餓? "?類μ넇????已??援???롫즲?????젻雅뚯눘?놅쭖???쇰뻻 筌≪뼚釉섋퉪?⑥쓺??"
                """;

        String user = """
                [野껊슣???袁⑥컭?? %s
                [?얜챷??? %s
                [?癒??筌욌뜆?? %s
                [?뚢뫂???쎈뱜] %s
                [?袁⑤궖 ?귐딅뮞????됱몵筌?獄쏆꼷?? ??곸몵筌???몄셽?怨몄몵嚥???쀬겱)] %s
                """.formatted(
                det.domain(),
                det.wrongTerm(),
                userQuery == null ? "" : userQuery,
                (insufficient ? "context_missing" : "evidence_present"),
                (candidates == null || candidates.isEmpty() ? "(??곸벉)" : String.join(", ", candidates))
        );

        try {
            PromptContext ctx = PromptContext.builder()
                    .systemInstruction(system)
                    .userQuery(user)
                    .build();
            String fallbackSuggestionPrompt = promptBuilder.build(ctx);
            var res = llm.chat(UserMessage.from(fallbackSuggestionPrompt));
            String out = (res == null || res.aiMessage() == null) ? null : res.aiMessage().text();
            return (out == null || out.isBlank()) ? templateFallback(det, candidates, userQuery) : out.trim();
        } catch (Exception e) {
            log.debug("[SmartFallback] ChatModel fallback failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return templateFallback(det, candidates, userQuery);
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    /**
     * ?怨멸쉭 野껉퀗?든몴????젻雅뚯눖???醫됲뇣 API.
     * 疫꿸퀣??maybeSuggest(/* ... *&#47;) 嚥≪뮆?????뽯툧 ??용뮞?紐? ??고?
     * ?뚢뫂???쎈뱜 ?봔鈺???뽯툧 鈺곕똻???????獄쏅?源??곗쨮 isFallback???怨쀭뀱.
     */
    public FallbackResult maybeSuggestDetailed(String query,
                                               String joinedContext,
                                               String answerDraft) {
        final String safeAnswer = (answerDraft == null) ? "" : answerDraft.trim();
        final boolean ctxEmpty  = !StringUtils.hasText(joinedContext)
                || "?類ｋ궖 ??곸벉".equalsIgnoreCase(safeAnswer);
        final String suggestion = maybeSuggest(query, joinedContext, safeAnswer);
        final boolean isFallback = ctxEmpty || StringUtils.hasText(suggestion);
        // When a fallback has occurred, log the knowledge gap for later autonomous exploration.
        if (isFallback && gapLogger != null && knowledgeBase != null && subjectResolver != null) {
            try {
                String domain = knowledgeBase.inferDomain(query);
                String subj   = subjectResolver.resolve(query, domain).orElse(null);
                gapLogger.logEvent(query, domain, subj, null);
            } catch (Exception e) {
                log.debug("[SmartFallback] fail-soft stage={}", "knowledgeGap.logEvent");
            }
        }
        return new FallbackResult(suggestion, isFallback);
    }

    /** OpenAI ??쑨?????筌ㅼ뮇???뽰벥 ??됱벥獄쏅뗀????덇땀 ??용뮞??*/
    private String templateFallback(FallbackHeuristics.Detection det, List<String> cand, String q) {
        StringBuilder sb = new StringBuilder();
        sb.append(det.wrongTerm())
                .append(" appears mismatched for domain ").append(det.domain()).append(". ")
                .append("Check the requested subject and available evidence. queryHash12=")
                .append(SafeRedactor.hash12(q)).append("\n");
        if (cand != null && !cand.isEmpty()) {
            sb.append("揶쎛?館釉??袁⑤궖: ").append(String.join(", ", cand)).append("\n");
        }
        sb.append("?類μ넇????已??援???롫즲?????젻雅뚯눘?놅쭖???쇰뻻 筌≪뼚釉섋퉪?⑥쓺??");
        return sb.toString();
    }
}
