package com.abandonware.ai.predict.tree;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/predict/tree")
@ConditionalOnProperty(prefix = "feature.predict-tree", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PredictTreeController {

    private final ScenarioExpander expander = new ScenarioExpander();
    private final TreeSerializer serializer = new TreeSerializer();

    @GetMapping(produces = "application/json")
    public Map<String, Object> buildGet(@RequestParam(name = "question", required = false) String question) {
        return buildInternal(question == null ? "" : question);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Map<String, Object> buildPost(@RequestBody Map<String, Object> req) {
        String q = String.valueOf(req.getOrDefault("question", ""));
        return buildInternal(q);
    }

    private Map<String, Object> buildInternal(String q) {
        TreeNode root = expander.expand(q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", q);
        out.put("mermaid", serializer.toMermaid(root));
        out.put("dot", serializer.toDot(root));
        return out;
    }
}
