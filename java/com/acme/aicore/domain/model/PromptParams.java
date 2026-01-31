package com.acme.aicore.domain.model;


/**
 * Parameters controlling prompt construction such as how many documents to
 * include.  This class can be extended with additional fields (temperature,
 * stop sequences, etc.) as needed.  Default values are provided for
 * convenience.
 */
public class PromptParams {
    private int maxCtx = 10;

    public int maxCtx() {
        return maxCtx;
    }

    public void setMaxCtx(int maxCtx) {
        this.maxCtx = maxCtx;
    }

    public static PromptParams defaults() {
        return new PromptParams();
    }
}