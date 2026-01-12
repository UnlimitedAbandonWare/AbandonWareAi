package com.abandonware.ai.predict.tree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple tree node model for predict-tree endpoint.
 */
public class TreeNode {

    public final String id;
    public final String label;
    public final List<TreeNode> children = new ArrayList<>();

    public TreeNode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static TreeNode root(String label) {
        return new TreeNode("root", label);
    }

    public static TreeNode hypo(String label) {
        return hypo(label, "", 0);
    }

    public static TreeNode check(String label) {
        return check(label, "", 0);
    }

    public static TreeNode hypo(String label, String seed, int index) {
        return new TreeNode(deterministicId(seed, "hypo", label, index), label);
    }

    public static TreeNode check(String label, String seed, int index) {
        return new TreeNode(deterministicId(seed, "check", label, index), label);
    }

    public void addChild(TreeNode child) {
        this.children.add(child);
    }

    public String labelWithProb(double p) {
        return label + " (p=" + String.format(Locale.ROOT, "%.2f", p) + ")";
    }

    private static String deterministicId(String seed, String kind, String label, int index) {
        String in = (seed == null ? "" : seed) + "|" + kind + "|" + index + "|" + (label == null ? "" : label);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
            // short, url-safe id
            return kind + "_" + toHex(d, 8);
        } catch (Exception e) {
            return kind + "_" + Math.abs(in.hashCode());
        }
    }

    private static String toHex(byte[] bytes, int nBytes) {
        int len = Math.min(bytes.length, Math.max(1, nBytes));
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit((bytes[i] >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return sb.toString();
    }
}
