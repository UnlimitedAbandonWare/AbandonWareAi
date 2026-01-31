package com.abandonware.ai.service.rag.model;

public class Query {
    private final String text;
    public Query(String text) { this.text = text; }
    public String getText() { return text; }
}