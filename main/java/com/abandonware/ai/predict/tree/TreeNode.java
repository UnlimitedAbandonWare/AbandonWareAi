package com.abandonware.ai.predict.tree;
import java.util.*;
public class TreeNode {
    public final String id;
    public final String label;
    public final List<TreeNode> children = new ArrayList<>();
    public TreeNode(String id, String label){ this.id=id; this.label=label==null? "" : label; }
    public static TreeNode root(String label){ return new TreeNode("root", label); }
    public TreeNode addChild(TreeNode n){ this.children.add(n); return this; }
    public static TreeNode hypo(String label){ return new TreeNode(UUID.randomUUID().toString(), label); }
    public static TreeNode check(String label){ return new TreeNode(UUID.randomUUID().toString(), label); }
    public String labelWithProb(double p){ return label + " (p=" + String.format(java.util.Locale.ROOT, "%.2f", p) + ")"; }
}
