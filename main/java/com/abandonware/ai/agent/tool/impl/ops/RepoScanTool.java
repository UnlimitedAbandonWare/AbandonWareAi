package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class RepoScanTool implements AgentTool {
    @Override
    public String id() {
        return "repo.scan";
    }

    @Override
    public String description() {
        return "Count active source/resource files and structural signals without returning file contents.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path repoRoot = Path.of(".").toAbsolutePath().normalize();
        out.put("rootHash", SafeRedactor.hashValue(repoRoot.toString()));
        out.put("rootLength", repoRoot.toString().length());
        out.put("mainJava", scan(Path.of("main", "java"), ".java"));
        out.put("mainResources", scan(Path.of("main", "resources"), null));
        out.put("testJava", scan(Path.of("src", "test", "java"), ".java"));
        out.put("appJavaClean", scan(Path.of("app", "src", "main", "java_clean"), ".java"));
        out.put("projectSrcMainJavaExists", Files.exists(Path.of("project", "src", "main", "java")));
        out.put("appSrcMainJavaExists", Files.exists(Path.of("app", "src", "main", "java")));
        return ToolResponse.ok().put("repoScan", out);
    }

    private static Map<String, Object> scan(Path root, String suffix) {
        Map<String, Object> out = new LinkedHashMap<>();
        String rawPath = root.toString();
        out.put("pathHash", SafeRedactor.hashValue(rawPath));
        out.put("pathLength", rawPath.length());
        out.put("exists", Files.exists(root));
        if (!Files.exists(root)) {
            out.put("fileCount", 0);
            return out;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            long count = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.getFileName().toString().endsWith(suffix))
                    .count();
            out.put("fileCount", count);
        } catch (IOException ex) {
            traceSuppressed("scan", root, ex);
            out.put("error", "repo_scan_failed");
        }
        return out;
    }

    private static void traceSuppressed(String stage, Path root, Throwable error) {
        String raw = root == null ? null : root.toString();
        TraceStore.put("agent.ops.repoScan.suppressed", true);
        TraceStore.put("agent.ops.repoScan.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.ops.repoScan.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.ops.repoScan.suppressed.pathHash", SafeRedactor.hashValue(raw));
        TraceStore.put("agent.ops.repoScan.suppressed.pathLength", raw == null ? 0 : raw.length());
    }
}
