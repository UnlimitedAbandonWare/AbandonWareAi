package com.example.lms.assist;

import java.time.Instant;

/** Quoted, untrusted data; co-mention does not establish causation or verification. */
public record MemoryEvidence(String evidenceId,String sourceId,long sourceRevision,String ownerNamespace,
        String sourceRole,String assertionType,String text,Instant eventTime,Instant recordedAt,
        Instant validFrom,Instant validTo,String supersedesId,Instant deletedAt,String verificationReference) {
    public MemoryEvidence {
        if(!java.util.Set.of("USER_REPORTED","VERIFIED","HYPOTHESIS","ASSISTANT_GENERATED").contains(assertionType)
            ||("VERIFIED".equals(assertionType)&&(verificationReference==null||verificationReference.isBlank())))
            throw new IllegalArgumentException("invalid_memory_provenance");
    }
    @Override public String toString(){return "MemoryEvidence[redacted]";}
}
