package com.example.lms.assist;

/** Constructed only after producer/owner binding. Never a public request body. */
public record FocusMemoryScope(String namespace,long consentRevision,long indexRevision,int policyRevision,boolean recallEnabled) {
    public FocusMemoryScope {if(namespace==null||!namespace.matches("[a-f0-9]{64}")||policyRevision!=1)throw new IllegalArgumentException("invalid_focus_scope");}
    @Override public String toString(){return "FocusMemoryScope[redacted]";}
}
