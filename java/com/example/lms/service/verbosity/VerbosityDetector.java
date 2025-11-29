package com.example.lms.service.verbosity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;




@Component
public class VerbosityDetector {

    private static final Pattern ULTRA = Pattern.compile("(아주\\s*자세|극도로|논문급|ultra)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEEP  = Pattern.compile("(상세히|자세히|깊게|원리부터|사례까지|deep)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEV   = Pattern.compile("(개발자|코드|API|소스|예제)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PM    = Pattern.compile("(기획|PM|로드맵)", Pattern.CASE_INSENSITIVE);

    @Value("${abandonware.answer.detail.min-words.brief:120}")   private int minBrief;
    @Value("${abandonware.answer.detail.min-words.standard:250}")private int minStd;
    @Value("${abandonware.answer.detail.min-words.deep:600}")    private int minDeep;
    @Value("${abandonware.answer.detail.min-words.ultra:1000}")  private int minUltra;

    @Value("${abandonware.answer.token-out.brief:512}")   private int tokBrief;
    @Value("${abandonware.answer.token-out.standard:1024}")private int tokStd;
    @Value("${abandonware.answer.token-out.deep:2048}")    private int tokDeep;
    @Value("${abandonware.answer.token-out.ultra:3072}")   private int tokUltra;

    public VerbosityProfile detect(String query) {
        String hint = "standard";
        if (query != null) {
            if (ULTRA.matcher(query).find()) hint = "ultra";
            else if (DEEP.matcher(query).find()) hint = "deep";
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
        String audience = (query != null && DEV.matcher(query).find()) ? "dev"
                : (query != null && PM.matcher(query).find())  ? "pm"
                : "enduser";
        return new VerbosityProfile(hint, minWords, tokOut, audience, "inline", List.of());
    }
}