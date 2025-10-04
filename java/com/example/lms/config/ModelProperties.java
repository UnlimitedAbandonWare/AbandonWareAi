package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * Centralised configuration for chat model identifiers.  This class replaces
 * the previous record implementation to allow sensible defaults to be
 * specified when no properties are provided.  The values are injected
 * from {@code openai.chat.model.*} properties.  When those properties are
 * absent, the fields fall back to the default and MOE models defined here.
 */

@ConfigurationProperties(prefix = "openai.chat.model")
public class ModelProperties {

    /**
     * The identifier of the default/mini chat model.  Use a small, cost‑
     * efficient model when the task does not require high fidelity.  Defaults
     * to {@code gpt-5-mini} when unspecified.
     */
    private String aDefault = "gpt-5-mini";

    /**
     * The identifier of the mixture‑of‑experts (MOE) chat model.  This model
     * offers higher quality at the expense of cost and latency.  Defaults to
     * {@code gpt-5-chat-latest} when unspecified.
     */
    private String moe = "gpt-5-chat-latest";

    public String getaDefault() {
        return aDefault;
    }

    public void setaDefault(String aDefault) {
        this.aDefault = aDefault;
    }

    public String getMoe() {
        return moe;
    }

    public void setMoe(String moe) {
        this.moe = moe;
    }
}