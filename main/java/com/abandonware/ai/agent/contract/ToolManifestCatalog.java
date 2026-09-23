package com.abandonware.ai.agent.contract;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ToolManifestCatalog {
    public static final String REPORT_PATH = "build/reports/codex/tool-contract-report.json";

    private static final List<String> MANIFEST_CANDIDATES = List.of(
            "tool_manifest__kchat_gpt_pro.json",
            "docs/tool_manifest__kchat_gpt_pro.json",
            "app/resources/docs/tool_manifest__kchat_gpt_pro.json"
    );

    private final ObjectMapper objectMapper;

    public ToolManifestCatalog() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            this.objectMapper.findAndRegisterModules();
        } catch (Throwable ignore) {
            traceSuppressed("objectMapper.findAndRegisterModules", ignore);
            // best-effort
        }
    }

    public ToolManifestSnapshot load() {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        for (String candidate : MANIFEST_CANDIDATES) {
            ClassPathResource resource = new ClassPathResource(candidate);
            if (!resource.exists()) {
                warnings.add(event("manifest_candidate_missing", candidate, null));
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                Map<String, ToolManifestEntry> entries = parseEntries(candidate, root, issues, warnings);
                return new ToolManifestSnapshot(candidate, Collections.unmodifiableMap(entries),
                        List.copyOf(issues), List.copyOf(warnings));
            } catch (Exception ex) {
                traceSuppressed("manifest.read", ex);
                issues.add(event("manifest_read_failed", candidate, "manifest_read_failed"));
            }
        }

        issues.add(event("manifest_not_found", String.join(",", MANIFEST_CANDIDATES), null));
        return new ToolManifestSnapshot("", Map.of(), List.copyOf(issues), List.copyOf(warnings));
    }

    public Map<String, Object> validate(ToolRegistry registry) {
        ToolManifestSnapshot snapshot = load();
        List<Map<String, Object>> issues = new ArrayList<>(snapshot.issues());
        List<Map<String, Object>> warnings = new ArrayList<>(snapshot.warnings());

        Set<String> manifestIds = new TreeSet<>(snapshot.entries().keySet());
        Set<String> registryIds = registry == null
                ? Set.of()
                : registry.all().stream().map(AgentTool::id).collect(Collectors.toCollection(TreeSet::new));

        for (ToolManifestEntry entry : snapshot.entries().values()) {
            if (entry.enabled() && !registryIds.contains(entry.id())) {
                issues.add(event("missing_enabled_tool_in_registry", entry.id(), null));
            } else if (!entry.enabled() && !registryIds.contains(entry.id())) {
                warnings.add(event("disabled_manifest_reference_not_registered", entry.id(), safeReason(entry.disabledReason())));
            }
        }
        for (String id : registryIds) {
            if (!manifestIds.contains(id)) {
                issues.add(event("missing_in_manifest", id, null));
            }
        }
        int missingInManifestCount = Math.max(0, registryIds.size() - intersectionSize(registryIds, manifestIds));
        int missingInRegistryCount = 0;
        for (ToolManifestEntry entry : snapshot.entries().values()) {
            if (entry.enabled() && !registryIds.contains(entry.id())) {
                missingInRegistryCount++;
            }
        }
        TraceStore.put("toolManifest.registeredCount", registryIds.size());
        TraceStore.put("toolManifest.manifestCount", manifestIds.size());
        TraceStore.put("toolManifest.missingInManifestCount", missingInManifestCount);
        TraceStore.put("toolManifest.missingInRegistryCount", missingInRegistryCount);
        TraceStore.put("toolManifest.snapshotAt", Instant.now().toString());
        if (registry != null) {
            registry.duplicateToolIdCounts().forEach((id, count) ->
                    issues.add(event("duplicate_tool_id", id, "count=" + count)));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", issues.isEmpty());
        report.put("resourcePathHash", SafeRedactor.hashValue(snapshot.resourcePath()));
        report.put("resourcePathLength", snapshot.resourcePath() == null ? 0 : snapshot.resourcePath().length());
        report.put("manifestIds", manifestIds);
        report.put("registryIds", registryIds);
        report.put("issueCount", issues.size());
        report.put("warningCount", warnings.size());
        report.put("issues", issues);
        report.put("warnings", warnings);
        report.put("reportSchema", "agent-tool-contract.v1");
        return report;
    }

    public Map<String, Object> writeValidationReport(ToolRegistry registry) {
        Map<String, Object> report = validate(registry);
        try {
            Path path = Path.of(REPORT_PATH);
            Files.createDirectories(path.getParent());
            byte[] bytes = objectMapper.writeValueAsBytes(SafeRedactor.diagnosticValue("toolContractReport", report, 4000));
            Files.write(path, bytes);
            String absolutePath = path.toAbsolutePath().toString();
            report.put("reportPathId", sha256(REPORT_PATH.getBytes(StandardCharsets.UTF_8)).substring(0, 12));
            report.put("reportPathHash", SafeRedactor.hashValue(absolutePath));
            report.put("reportPathLength", absolutePath.length());
            report.put("sha256", sha256(bytes));
            report.put("sizeBytes", bytes.length);
        } catch (Exception ex) {
            traceSuppressed("report.write", ex);
            report.put("reportWriteError", "report_write_failed");
        }
        return report;
    }

    private Map<String, ToolManifestEntry> parseEntries(String resourcePath,
                                                        JsonNode root,
                                                        List<Map<String, Object>> issues,
                                                        List<Map<String, Object>> warnings) {
        Map<String, ToolManifestEntry> entries = new LinkedHashMap<>();
        JsonNode tools = root.path("tools");
        if (!tools.isArray()) {
            issues.add(event("manifest_schema_error", resourcePath, "tools_not_array"));
            return entries;
        }
        for (JsonNode node : tools) {
            String id = firstText(node, "id", "name");
            if (id == null || id.isBlank()) {
                issues.add(event("manifest_schema_error", resourcePath, "missing_id"));
                continue;
            }
            id = id.trim();
            if (entries.containsKey(id)) {
                issues.add(event("duplicate_manifest_id", id, resourcePath));
                continue;
            }
            boolean enabled = node.path("enabled").asBoolean(true);
            String risk = textOr(node, "risk", "read_only");
            boolean readOnly = node.has("readOnly")
                    ? node.path("readOnly").asBoolean()
                    : "read_only".equalsIgnoreCase(risk);
            boolean ownerTokenRequired = node.path("ownerTokenRequired").asBoolean(!readOnly);
            int maxOutputBytes = Math.max(1024, node.path("maxOutputBytes").asInt(65536));
            List<String> scopes = stringList(node.get("scopes"));
            if (scopes.isEmpty()) {
                scopes = stringList(node.get("permissions"));
            }
            Map<String, Object> rawSummary = new LinkedHashMap<>();
            rawSummary.put("hasEndpoint", hasText(node, "endpoint"));
            rawSummary.put("hasDisabledReason", hasText(node, "disabledReason"));
            rawSummary.put("source", resourcePath);

            ToolManifestEntry entry = new ToolManifestEntry(
                    id,
                    enabled,
                    textOr(node, "description", textOr(node, "title", "")),
                    risk,
                    List.copyOf(scopes),
                    readOnly,
                    ownerTokenRequired,
                    maxOutputBytes,
                    node.path("returnsLargePayloadByReference").asBoolean(false),
                    textOr(node, "endpoint", ""),
                    textOr(node, "disabledReason", ""),
                    Map.copyOf(rawSummary)
            );
            entries.put(id, entry);
            if (!enabled && entry.disabledReason().isBlank()) {
                warnings.add(event("disabled_tool_missing_reason", id, null));
            }
        }
        return entries;
    }

    private static Map<String, Object> event(String issue, String id, String detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue", issue);
        out.put("id", id == null ? "" : id);
        if (detail != null && !detail.isBlank()) {
            out.put("detail", SafeRedactor.safeMessage(detail, 240));
        }
        return out;
    }

    private static String safeReason(String reason) {
        return SafeRedactor.traceLabelOrFallback(reason, "unknown");
    }

    private static int intersectionSize(Set<String> left, Set<String> right) {
        int count = 0;
        for (String value : left) {
            if (right.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = textOr(node, name, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String textOr(JsonNode node, String name, String fallback) {
        if (node == null || name == null) {
            return fallback;
        }
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? fallback : value.asText(fallback).trim();
    }

    private static boolean hasText(JsonNode node, String name) {
        return !textOr(node, name, "").isBlank();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        } else {
            String raw = node.asText("").trim();
            for (String token : raw.split(",")) {
                String value = token.trim();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format(Locale.ROOT, "%02x", b));
            }
            return out.toString();
        } catch (Exception ex) {
            traceSuppressed("sha256.digest", ex);
            return Integer.toHexString(new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8).hashCode());
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.put("agent.toolManifest.suppressed", true);
        TraceStore.put("agent.toolManifest.suppressed.stage", stage);
        TraceStore.put("agent.toolManifest.suppressed.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public Collection<String> candidatePaths() {
        return MANIFEST_CANDIDATES;
    }
}
