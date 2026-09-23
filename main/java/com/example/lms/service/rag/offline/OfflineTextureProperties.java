package com.example.lms.service.rag.offline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.offline-texture")
public class OfflineTextureProperties {
    private boolean enabled = true;
    private boolean writeEnabled = false;
    private String manifestPath = "data/offline-texture/offline_texture_manifest.jsonl";
    private String snapshotDir = "data/offline-texture/snapshots";
    private int ttlHours = 24;
    private int maxSnapshots = 500;
    private int maxAnchors = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public void setManifestPath(String manifestPath) {
        this.manifestPath = manifestPath;
    }

    public String getSnapshotDir() {
        return snapshotDir;
    }

    public void setSnapshotDir(String snapshotDir) {
        this.snapshotDir = snapshotDir;
    }

    public int getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(int ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getMaxSnapshots() {
        return maxSnapshots;
    }

    public void setMaxSnapshots(int maxSnapshots) {
        this.maxSnapshots = maxSnapshots;
    }

    public int getMaxAnchors() {
        return maxAnchors;
    }

    public void setMaxAnchors(int maxAnchors) {
        this.maxAnchors = maxAnchors;
    }
}
