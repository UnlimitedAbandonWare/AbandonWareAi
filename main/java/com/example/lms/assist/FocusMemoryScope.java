package com.example.lms.assist;

/** Constructed only after producer/owner binding. Never a public request body. */
public record FocusMemoryScope(String namespace,long consentRevision,long indexRevision,int policyRevision,boolean recallEnabled,NovaFocusSettings.Memory memory) {
    public FocusMemoryScope(String namespace,long consentRevision,long indexRevision,int policyRevision,boolean recallEnabled){
        this(namespace,consentRevision,indexRevision,policyRevision,recallEnabled,null);
    }
    public FocusMemoryScope {if(namespace==null||!namespace.matches("[a-f0-9]{64}")||policyRevision!=1)throw new IllegalArgumentException("invalid_focus_scope");}
    public NovaFocusSettings.Memory memoryOrDefault(){return memory==null?NovaFocusSettings.Memory.defaults():memory;}
    @Override public String toString(){return "FocusMemoryScope[redacted]";}
}
