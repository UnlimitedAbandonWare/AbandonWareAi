package com.example.lms.assist;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Explicitly selected facts only. Search projections are disposable; this is the authority. */
@Entity @Table(name="focus_memory_fact",indexes=@Index(name="focus_memory_scope_current",columnList="scope_id,current_revision"))
@Getter @Setter @NoArgsConstructor
public class FocusMemoryFact {
    @Id @Column(length=80) private String id;
    @Column(name="scope_id",nullable=false,length=64) private String scopeId;
    @Column(nullable=false,length=36) private String sourceId;
    @Column(nullable=false) private long revision;
    @Column(name="current_revision",nullable=false) private boolean currentRevision;
    @Column(nullable=false) private long consentRevision;
    @Column(length=80) private String supersedesId;
    @Column(columnDefinition="TEXT") private String text;
    @Column(columnDefinition="TEXT") private String entitiesJson;
    @Column(length=32,nullable=false) private String assertionType;
    @Column(nullable=false) private Instant eventTime;
    @Column(nullable=false) private Instant recordedAt;
    private Instant validTo;
    private Instant deletedAt;
    @Column(length=200) private String embeddingFingerprint;
    @Lob private byte[] embedding;
    @Override public String toString(){return "FocusMemoryFact[redacted]";}
}
