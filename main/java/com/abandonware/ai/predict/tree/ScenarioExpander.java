package com.abandonware.ai.predict.tree;
import java.util.*;
public class ScenarioExpander {
    public TreeNode expand(String q){
        TreeNode root = TreeNode.root(q == null ? "" : q);
        for (String s : Arrays.asList("정의/범위 확인","동의어·오타 보정","관계·인과 가설")){
            root.addChild(TreeNode.hypo(s));
        }
        return root;
    }
}
