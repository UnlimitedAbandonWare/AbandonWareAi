package com.abandonware.ai.predict.tree;
public class TreeSerializer {
    private String idOf(TreeNode n){ return n.id.replace("-", "").replace(" ", ""); }
    private String esc(String s){
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    public String toMermaid(TreeNode root){
        StringBuilder sb = new StringBuilder("graph TD\n");
        emit(root, null, sb, true);
        return sb.toString();
    }
    public String toDot(TreeNode root){
        StringBuilder sb = new StringBuilder("digraph G {\nrankdir=LR;\n");
        emit(root, null, sb, false);
        sb.append("}\n");
        return sb.toString();
    }
    private void emit(TreeNode n, TreeNode parent, StringBuilder sb, boolean mermaid){
        if (n==null) return;
        String id = idOf(n);
        String label = esc(n.label);
        if (mermaid){
            sb.append(id).append("[\"").append(label).append("\"];\n");
        } else {
            sb.append(id).append("[label=\"").append(label).append("\"];\n");
        }
        if (parent != null){
            String pid = idOf(parent);
            if (mermaid) sb.append(pid).append("-->").append(id).append("\n");
            else sb.append(pid).append("->").append(id).append(";\n");
        }
        for (TreeNode c : n.children) emit(c, n, sb, mermaid);
    }
}
