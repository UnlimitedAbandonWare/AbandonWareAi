package com.abandonware.ai.predict.tree;

import java.util.List;
import java.util.Locale;

/**
 * Expands a question into a small deterministic scenario tree.
 */
public class ScenarioExpander {

    private final ProbabilityEngine probabilityEngine = new ProbabilityEngine();

    public TreeNode expand(String question) {
        String seed = question == null ? "" : question.strip();

        TreeNode root = TreeNode.root("PredictTree");

        List<String> hypos = List.of(
                "Restate the question and constraints",
                "Generate 2-3 plausible scenarios",
                "List checks/assumptions to validate"
        );

        for (int i = 0; i < hypos.size(); i++) {
            String h = hypos.get(i);
            double p = probabilityEngine.score(seed, h, i);
            String label = h + " (p=" + String.format(Locale.ROOT, "%.2f", p) + ")";
            root.addChild(TreeNode.hypo(label, seed, i));
        }

        return root;
    }
}
