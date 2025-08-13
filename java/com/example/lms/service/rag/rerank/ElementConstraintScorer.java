// src/main/java/com/example/lms/service/rag/rerank/ElementConstraintScorer.java
package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.genshin.ElementLexicon;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

@Component
public class ElementConstraintScorer {

    private final QueryContextPreprocessor pre;

    public ElementConstraintScorer(QueryContextPreprocessor preprocessor) {
        this.pre = preprocessor;
    }

    /** allowed/discouraged 원소 기반으로 현재 순위를 보정해 재정렬한다. */
    public List<Content> rescore(String queryText, List<Content> ranked) {
        if (ranked == null || ranked.isEmpty()) return ranked;

        Set<String> allow = pre.allowedElements(queryText);
        Set<String> block = pre.discouragedElements(queryText);
        boolean hasPolicy = (allow != null && !allow.isEmpty()) || (block != null && !block.isEmpty());
        if (!hasPolicy) return ranked;

        class Scored {
            final Content c;
            final double s;
            Scored(Content c, double s) { this.c = c; this.s = s; }
        }

        List<Scored> list = new ArrayList<>();
        int idx = 0;
        for (Content c : ranked) {
            idx++;
            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElseGet(c::toString);
            double base = 1.0 / idx; // 기존 상위 가중치 유지
            String el = "";
            try {
                Object tag = (c.metadata() != null) ? c.metadata().get("elementTag") : null;
                el = (tag != null) ? String.valueOf(tag) : ElementLexicon.inferElement(text);
            } catch (Exception ignore) { /* 안전 무시 */ }
            double delta = 0.0;
            if (StringUtils.hasText(el)) {
                if (!allow.isEmpty() && allow.contains(el)) delta += 2.0;
                if (!block.isEmpty() && block.contains(el)) delta -= 2.5;
            }
            list.add(new Scored(c, base + delta));
        }
        list.sort((a, b) -> Double.compare(b.s, a.s));
        List<Content> out = new ArrayList<>();
        for (Scored sc : list) out.add(sc.c);
        return out;
    }
}
