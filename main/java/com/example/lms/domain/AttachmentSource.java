package com.example.lms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable attachment authority. Bytes remain in the existing upload store; indexes are derived. */
@Entity
@Table(name="attachment_source", indexes=@Index(name="idx_attachment_source_session", columnList="session_id"))
@Getter @Setter @NoArgsConstructor
public class AttachmentSource {
    @Id @Column(length=36) private String id;
    @Version private long rowVersion;
    @Column(nullable=false,length=64) private String ownerNamespace;
    @Column(name="session_id",length=128) private String sessionId;
    @Column(nullable=false,length=16) private String channel="GENERAL";
    @Column(length=1024) private String originalName;
    @Column(length=255) private String contentType;
    @Column(nullable=false,length=2048) private String storageLocator;
    private long sizeBytes;
    @Column(nullable=false,length=64) private String contentSha256;
    private long sourceRevision=1;
    @Column(length=80) private String parserVersion="";
    private long retainedAt;
    private long expiresAt;
    private boolean tombstone;
    private long consentEpoch;
    @Lob @Column(columnDefinition="LONGTEXT") private String unitsJson;
    @Column(length=40) private String textState="RECEIVED";
    @Column(length=40) private String graphState="NOT_INDEXED";
    @Column(length=40) private String vectorState="NOT_INDEXED";
    @Column(length=120) private String failureReason="";
}
