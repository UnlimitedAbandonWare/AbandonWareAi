package com.example.lms.service.rag.graph;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.AttachmentOwnerIdentity;

import java.util.Optional;

/** Server-authorized ordinary-chat scope. Never construct it from query hints or JSON. */
public final class GeneralGraphScope {
    public static final String METADATA_KEY = "generalGraphScope";
    public static final String CHANNEL = "GENERAL";

    private final String ownerNamespace;
    private final long sessionId;
    private final long consentEpoch;
    private final boolean memoryEnabled;

    private GeneralGraphScope(String ownerNamespace, long sessionId) {
        this(ownerNamespace, sessionId, 0, false);
    }

    private GeneralGraphScope(String ownerNamespace, long sessionId, long consentEpoch, boolean memoryEnabled) {
        this.ownerNamespace = ownerNamespace;
        this.sessionId = sessionId;
        this.consentEpoch = consentEpoch;
        this.memoryEnabled = memoryEnabled;
    }

    public static Optional<GeneralGraphScope> authorize(ChatSession session, String username, String ownerKey) {
        if (session == null || session.getId() == null || session.getId() <= 0) return Optional.empty();
        try {
            String persistedOwner = session.getAdministrator() != null
                    ? AttachmentOwnerIdentity.forAdministrator(session.getAdministrator().getUsername()).hash()
                    : AttachmentOwnerIdentity.forAnonymous(session.getOwnerKey()).hash();
            String requester = AttachmentOwnerIdentity.forActor(username, ownerKey).hash();
            return persistedOwner.equals(requester)
                    ? Optional.of(new GeneralGraphScope(persistedOwner, session.getId())) : Optional.empty();
        } catch (IllegalArgumentException missingOwner) {
            return Optional.empty();
        }
    }

    public String ownerNamespace() { return ownerNamespace; }
    public long sessionId() { return sessionId; }
    public String channel() { return CHANNEL; }
    public long consentEpoch() { return consentEpoch; }
    public boolean memoryEnabled() { return consentEpoch > 0 && memoryEnabled; }

    public String indexNamespace() {
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                ownerNamespace + ":" + sessionId + ":" + CHANNEL + ":" + consentEpoch);
    }

    GeneralGraphScope withPolicy(long epoch, boolean enabled) {
        if (epoch <= 0) throw new IllegalArgumentException("invalid_graph_epoch");
        return new GeneralGraphScope(ownerNamespace, sessionId, epoch, enabled);
    }

    /** Stored index claims still require relational owner, policy and source validation. */
    static GeneralGraphScope indexClaim(String owner, long session, long epoch) {
        if (owner == null || !owner.matches("[a-f0-9]{64}") || session <= 0 || epoch <= 0)
            throw new IllegalArgumentException("invalid_graph_claim");
        return new GeneralGraphScope(owner, session, epoch, true);
    }

    boolean owns(ChatSession session) {
        if (session == null || !matchesSession(session.getId())) return false;
        try {
            String persistedOwner = session.getAdministrator() != null
                    ? AttachmentOwnerIdentity.forAdministrator(session.getAdministrator().getUsername()).hash()
                    : AttachmentOwnerIdentity.forAnonymous(session.getOwnerKey()).hash();
            return ownerNamespace.equals(persistedOwner);
        } catch (IllegalArgumentException missingOwner) {
            return false;
        }
    }

    public boolean matchesSession(Object candidate) {
        return candidate != null && Long.toString(sessionId).equals(candidate.toString());
    }

    @Override
    public String toString() { return "GeneralGraphScope[redacted]"; }
}
