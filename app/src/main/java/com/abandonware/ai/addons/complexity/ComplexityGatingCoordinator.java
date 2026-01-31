package com.abandonware.ai.addons.complexity;

import com.abandonware.ai.addons.config.AddonsProperties;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.complexity.ComplexityGatingCoordinator
 * Role: config
 * Dependencies: com.abandonware.ai.addons.config.AddonsProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.complexity.ComplexityGatingCoordinator
role: config
*/
public class ComplexityGatingCoordinator {
    private static final Logger log = Logger.getLogger(ComplexityGatingCoordinator.class.getName());
    private final QueryComplexityClassifier classifier;
    private final AddonsProperties props;

    public ComplexityGatingCoordinator(QueryComplexityClassifier classifier, AddonsProperties props) {
        this.classifier = classifier;
        this.props = props;
    }

    public RetrievalHints decide(String query, Locale locale, Map<String, Object> ctx) {
        if (!props.getComplexity().isEnabled()) {
            return new RetrievalHints(props.getWeb().getTopKDefault(), props.getVector().getTopKDefault(),
                    true, true, true, true, "default");
        }
        var cr = classifier.classify(query, locale);
        var baseWebK = props.getWeb().getTopKDefault();
        var baseVecK = props.getVector().getTopKDefault();

        int webK = 0, vecK = baseVecK;
        boolean useCE = true, useBE = true, pass2 = true, enableWeb = false;
        String routing = "default";

        switch (cr.tag()) {
            case SIMPLE -> {
                webK = 0; vecK = Math.min(8, baseVecK);
                useCE = false; pass2 = false; enableWeb = false;
            }
            case COMPLEX -> {
                webK = Math.min(baseWebK, 5); vecK = Math.max(10, baseVecK);
                useCE = true; pass2 = true; enableWeb = props.getWeb().isEnabled();
            }
            case WEB_REQUIRED -> {
                webK = Math.max(5, baseWebK); vecK = Math.max(12, baseVecK);
                useCE = true; pass2 = true; enableWeb = true;
            }
            case DOMAIN_SPECIFIC -> {
                webK = 0; vecK = Math.max(12, baseVecK);
                useCE = true; pass2 = true; enableWeb = false; routing = "domain_strict";
            }
        }
        final int __webK = webK; final int __vecK = vecK; log.fine(() -> "[complexity] tag=" + cr.tag() + " webK=" + __webK + " vecK=" + __vecK);
        return new RetrievalHints(webK, vecK, useCE, useBE, pass2, enableWeb, routing);
    }
}