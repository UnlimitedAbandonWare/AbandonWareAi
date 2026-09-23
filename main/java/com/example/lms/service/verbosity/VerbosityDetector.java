package com.example.lms.service.verbosity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;




@Component
public class VerbosityDetector {

    private static final Pattern ULTRA = Pattern.compile("(아주\\s*자세|극도로|논문급|ultra)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEEP  = Pattern.compile("(상세히|자세히|깊게|원리부터|사례까지|deep)", Pattern.CASE_INSENSITIVE);
	private static final Pattern ONE_OR_TWO_SENTENCES = Pattern.compile("((?:한|두)\\s*문장|(?:one|two)\\s+sentences?|single\\s+sentence)", Pattern.CASE_INSENSITIVE);
	private static final Pattern EXACT_COMPACT_OUTPUT = Pattern.compile(
			"((?:숫자|수치|값)\\s*만|(?:결과|정답)\\s*만\\s*(?:말해|답해|알려|출력해)(?:\\s*(?:줘|주세요))?\\s*(?:[.!?]\\s*)?$|only\\s+the\\s+(?:number|value)|(?:number|value)\\s+only|한\\s*줄|one[-\\s]+line|single[-\\s]+line)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern NAME_OR_SINGLE_WORD_OUTPUT = Pattern.compile(
			"(?:(?:이름|명칭|(?:한|1)\\s*(?:단어|낱말)(?:로)?)\\s*만\\s*(?:대답해|말해|답해|알려|출력해)(?:\\s*(?:줘|주세요))?"
					+ "|\\b(?:answer|reply|respond)\\s+(?:(?:in|with)\\s+)?(?:one|single|a\\s+single)[-\\s]+word(?:\\s+only)?)\\s*[.!?]*\\s*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern CONFLICTING_COMPACT_INSTRUCTION = Pattern.compile(
			"(\\b(?:do\\s+not|don't)\\b.{0,48}\\b(?:one|two|single)[-\\s]+(?:sentences?|lines?)\\b"
					+ "|\\bnot\\s+only\\s+the\\s+(?:number|value)\\b"
					+ "|\\bonly\\s+the\\s+(?:number|value)\\s+is\\s+(?:insufficient|not\\s+enough)\\b"
					+ "|\\b(?:one|single)[-\\s]+sentence\\s+(?:is\\s+(?:insufficient|not\\s+enough)|isn't\\s+enough)\\b"
					+ "|\\b(?:one|single)[-\\s]+line\\s+per\\s+(?:item|result)\\b"
					+ "|(?:한|두)\\s*문장(?:으로|만)?\\s*(?:답하지|말하지)\\s*말)"
			,
			Pattern.CASE_INSENSITIVE);
	private static final Pattern BRIEF = Pattern.compile("(간단히|간단하게|짧게|한\\s*줄|(?:한|두)\\s*문장|요약만|(?:one|two)\\s+sentences?|single\\s+sentence|tldr|tl;dr|brief)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEV   = Pattern.compile("(개발자|코드|API|소스|예제)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PM    = Pattern.compile("(기획|PM|로드맵)", Pattern.CASE_INSENSITIVE);

    @Value("${abandonware.answer.detail.min-words.brief:120}")   private int minBrief;
    @Value("${abandonware.answer.detail.min-words.standard:250}")private int minStd;
    @Value("${abandonware.answer.detail.min-words.deep:600}")    private int minDeep;
    @Value("${abandonware.answer.detail.min-words.ultra:1000}")  private int minUltra;

    @Value("${abandonware.answer.token-out.brief:800}")   private int tokBrief;
    @Value("${abandonware.answer.token-out.standard:1000}")private int tokStd;
    @Value("${abandonware.answer.token-out.deep:1500}")    private int tokDeep;
    @Value("${abandonware.answer.token-out.ultra:2200}")   private int tokUltra;

    public VerbosityProfile detect(String query) {
        String hint = "standard";
        boolean boundedOutputRequest = false;
        if (query != null) {
            boolean conflictingCompactInstruction = conflictingCompactInstruction(query);
            boundedOutputRequest = !conflictingCompactInstruction
                    && (ONE_OR_TWO_SENTENCES.matcher(query).find()
                    || EXACT_COMPACT_OUTPUT.matcher(query).find()
                    || NAME_OR_SINGLE_WORD_OUTPUT.matcher(query).find());
            if (boundedOutputRequest) hint = "brief";
            else if (ULTRA.matcher(query).find()) hint = "ultra";
            else if (DEEP.matcher(query).find()) hint = "deep";
	            else if (!conflictingCompactInstruction && BRIEF.matcher(query).find()) hint = "brief";
        }
        int minWords = switch (hint) {
            case "brief" -> minBrief;
            case "deep"  -> minDeep;
            case "ultra" -> minUltra;
            default      -> minStd;
        };
        int tokOut = switch (hint) {
            case "brief" -> tokBrief;
            case "deep"  -> tokDeep;
            case "ultra" -> tokUltra;
            default      -> tokStd;
        };
        if (boundedOutputRequest && "brief".equals(hint)) {
            minWords = 0;
            tokOut = tokBrief > 0 ? Math.min(tokBrief, 160) : 160;
        }
        String audience = (query != null && DEV.matcher(query).find()) ? "dev"
                : (query != null && PM.matcher(query).find())  ? "pm"
                : "enduser";
        return new VerbosityProfile(hint, minWords, tokOut, audience, "inline", List.of());
    }

    private static boolean conflictingCompactInstruction(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        boolean explicitlyNegatedCompactRequest = (lower.contains("do not") || lower.contains("don't"))
                && (lower.contains("one sentence")
                || lower.contains("two sentences")
                || lower.contains("single sentence")
                || lower.contains("one line")
                || lower.contains("single line")
                || lower.contains("one word")
                || lower.contains("single word")
                || lower.contains("only the number")
                || lower.contains("only the value")
                || lower.contains("number only")
                || lower.contains("value only"));
        return explicitlyNegatedCompactRequest
                || CONFLICTING_COMPACT_INSTRUCTION.matcher(lower).find();
    }
}
