package com.example.lms.assist;

import java.time.Instant;

/** Quoted, untrusted data; co-mention does not establish causation or verification. */
public record MemoryEvidence(String evidenceId,String sourceId,long sourceRevision,String ownerNamespace,
        String sourceRole,String assertionType,String text,Instant eventTime,Instant recordedAt,
        Instant validFrom,Instant validTo,String supersedesId,Instant deletedAt,String verificationReference,
        java.util.List<String> entities,String relationshipType) {
    public MemoryEvidence(String evidenceId,String sourceId,long sourceRevision,String ownerNamespace,
            String sourceRole,String assertionType,String text,Instant eventTime,Instant recordedAt,
            Instant validFrom,Instant validTo,String supersedesId,Instant deletedAt,String verificationReference) {
        this(evidenceId,sourceId,sourceRevision,ownerNamespace,sourceRole,assertionType,text,eventTime,recordedAt,
            validFrom,validTo,supersedesId,deletedAt,verificationReference,java.util.List.of(),"CO_MENTIONED_WITH");
    }
    public MemoryEvidence {
        entities=java.util.List.copyOf(entities==null?java.util.List.of():entities);
        if(entities.size()>8||!"CO_MENTIONED_WITH".equals(relationshipType))throw new IllegalArgumentException("invalid_memory_relationship");
        if(!java.util.Set.of("USER_REPORTED","VERIFIED","HYPOTHESIS","ASSISTANT_GENERATED").contains(assertionType)
            ||("VERIFIED".equals(assertionType)&&(verificationReference==null||verificationReference.isBlank())))
            throw new IllegalArgumentException("invalid_memory_provenance");
    }
    @Override public String toString(){return "MemoryEvidence[redacted]";}
}
