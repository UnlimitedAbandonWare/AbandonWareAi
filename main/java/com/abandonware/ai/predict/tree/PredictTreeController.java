package com.abandonware.ai.predict.tree;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/predict/tree")
public class PredictTreeController {
    private final ScenarioExpander exp = new ScenarioExpander();
    private final TreeSerializer ser = new TreeSerializer();
    @PostMapping
    public Map<String,Object> build(@RequestBody Map<String,Object> req){
        String q = String.valueOf(req.getOrDefault("question",""));
        TreeNode t = exp.expand(q);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("mermaid", ser.toMermaid(t));
        out.put("dot", ser.toDot(t));
        return out;
    }
}
