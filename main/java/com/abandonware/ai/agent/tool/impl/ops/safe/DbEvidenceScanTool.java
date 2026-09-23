package com.abandonware.ai.agent.tool.impl.ops.safe;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class DbEvidenceScanTool implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENTITY_ANNOTATION_PATTERN =
            Pattern.compile("(?m)^\\s*@(?:jakarta\\.persistence\\.)?Entity\\b");
    private static final Pattern JPA_REPOSITORY_EXTENDS_PATTERN =
            Pattern.compile("(?m)\\bextends\\s+[\\w.]*JpaRepository\\s*<");
    private static final Pattern CREATE_TABLE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?:(?:\"?([a-zA-Z_][\\w$]*)\"?)\\s*\\.\\s*)?\"?[a-zA-Z_][\\w$]*\"?"
    );
    private static final Pattern CREATE_VIEW_DECLARATION_PATTERN = Pattern.compile(
            "(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?view\\s+(?:(?:\"?([a-zA-Z_][\\w$]*)\"?)\\s*\\.\\s*)?\"?[a-zA-Z_][\\w$]*\"?"
    );
    private static final Pattern SECURITY_INVOKER_TRUE_PATTERN = Pattern.compile(
            "(?is)\\bsecurity_invoker\\s*=\\s*true\\b"
    );
    private static final Pattern ROW_LEVEL_SECURITY_ENABLE_PATTERN = Pattern.compile(
            "(?is)\\balter\\s+table\\s+(?:if\\s+exists\\s+)?(?:\"?public\"?\\s*\\.\\s*)?\"?[a-zA-Z_][\\w$]*\"?\\s+enable\\s+row\\s+level\\s+security\\b"
    );
    private static final Pattern ANON_GRANT_PATTERN = Pattern.compile("(?is)\\bgrant\\s+[^;]*\\bto\\s+anon\\b");
    private static final Pattern AUTHENTICATED_GRANT_PATTERN =
            Pattern.compile("(?is)\\bgrant\\s+[^;]*\\bto\\s+authenticated\\b");
    private static final Pattern PUBLIC_SECURITY_DEFINER_FUNCTION_PATTERN = Pattern.compile(
            "(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?function\\s+(?:\"?public\"?\\s*\\.\\s*)?\"?[a-zA-Z_][\\w$]*\"?\\s*\\([^;]*?\\bsecurity\\s+definer\\b"
    );
    private static final Pattern ROW_LEVEL_SECURITY_POLICY_PATTERN = Pattern.compile(
            "(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?policy\\b"
    );
    private static final Pattern MYSQL_DDL_HINT_PATTERN =
            Pattern.compile("(?is)\\b(?:AUTO_INCREMENT|INFORMATION_SCHEMA|ENGINE\\s*=|CHARSET\\s*=|DATETIME\\s*\\(|TINYINT\\s*\\()");
    private static final Pattern POSTGRES_SUPABASE_DDL_HINT_PATTERN =
            Pattern.compile("(?is)(?:\\brow\\s+level\\s+security\\b|\\bto\\s+(?:anon|authenticated)\\b|\\bsecurity\\s+definer\\b|\\bpublic\\s*\\.)");
    private static final List<String> GENERATED_DIR_EXCLUDES = List.of(
            "build", ".gradle", "node_modules", ".next", "out", "target", ".build",
            "__pycache__", ".git", "dist", "coverage"
    );

    private static final List<String> CONFIG_KEYS = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.jpa.hibernate.ddl-auto",
            "LMS_DB_URL",
            "LMS_DB_USERNAME",
            "LMS_DB_PASSWORD",
            "AWX_ADMIN_TOKEN",
            "DOMAIN_ALLOWLIST_ADMIN_TOKEN"
    );
    private static final List<String> SUPABASE_KEYS = List.of(
            "SUPABASE_URL",
            "SUPABASE_PROJECT_REF",
            "SUPABASE_ACCESS_TOKEN",
            "SUPABASE_PUBLISHABLE_KEY",
            "SUPABASE_PUBLISHABLE_KEYS",
            "SUPABASE_SECRET_KEY",
            "SUPABASE_SECRET_KEYS",
            "SUPABASE_ANON_KEY",
            "SUPABASE_SERVICE_ROLE_KEY",
            "SUPABASE_DB_URL",
            "NEXT_PUBLIC_SUPABASE_URL",
            "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY",
            "NEXT_PUBLIC_SUPABASE_ANON_KEY"
    );

    private final Path root;
    private final Environment environment;

    public DbEvidenceScanTool(Environment environment) {
        this(Path.of("."), environment);
    }

    DbEvidenceScanTool(Path root, Environment environment) {
        this.root = root == null ? Path.of(".") : root;
        this.environment = environment;
    }

    @Override
    public String id() {
        return "db_evidence_scan";
    }

    @Override
    public String description() {
        return "Summarize DB/JPA/NDJSON/config/runtime/Supabase evidence using count/hash/boolean fields only.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Path base = root.toAbsolutePath().normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "agent.db_evidence_scan.v1");
        out.put("rootHash", SafeRedactor.hashValue(base.toString()));
        out.put("rootLength", base.toString().length());
        out.put("dbGap", dbGap(base));
        out.put("jpa", jpa(base));
        out.put("ndjson", ndjson(base));
        out.put("config", config());
        out.put("runtime", runtime(base));
        out.put("supabase", supabase(base));
        out.put("postgres", postgres(base));
        out.put("evidenceStages", Map.of(
                "preprocessConfigEvidence", true,
                "middleNodeJpaAndNdjsonEvidence", true,
                "postprocessRuntimeAndSupabaseEvidence", true
        ));
        return ToolResponse.ok().put("dbEvidenceScan", out);
    }

    private static Map<String, Object> dbGap(Path base) {
        Path reportDir = base.resolve(Path.of("data", "db-gap-report"));
        Path scanner = base.resolve(Path.of("scripts", "db_gap_scanner.py"));
        Path gapMatrix = reportDir.resolve("gap_matrix.json");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scannerPresent", Files.isRegularFile(scanner));
        out.put("gapReportDirPresent", Files.isDirectory(reportDir));
        out.put("gapMatrixPresent", Files.isRegularFile(gapMatrix));
        out.put("gapMatrixBytes", size(gapMatrix));
        out.put("gapMatrixHash12", fileHash12(gapMatrix));
        out.put("gapReportFileCount", countFiles(reportDir, 4, List.of(".json", ".jsonl", ".ndjson", ".md")));
        return out;
    }

    private static Map<String, Object> jpa(Path base) {
        Path javaRoot = base.resolve(Path.of("main", "java"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("javaRootPresent", Files.isDirectory(javaRoot));
        out.put("javaFileCount", countFiles(javaRoot, 18, List.of(".java")));
        out.put("entityCount", countMatchingJavaFiles(javaRoot, DbEvidenceScanTool::containsEntityAnnotation));
        out.put("repositoryCount", countMatchingJavaFiles(javaRoot, DbEvidenceScanTool::containsJpaRepositoryExtends));
        out.put("ddlFileCount", countFiles(base.resolve(Path.of("main", "resources", "db", "ddl")), 4, List.of(".sql")));
        return out;
    }

    private static Map<String, Object> ndjson(Path base) {
        Path dataRoot = base.resolve("data");
        Map<String, Object> out = new LinkedHashMap<>();
        FileSummary summary = summarizeFiles(dataRoot, 8, List.of(".jsonl", ".ndjson"));
        out.put("dataRootPresent", Files.isDirectory(dataRoot));
        out.put("ndjsonFileCount", summary.count());
        out.put("ndjsonTotalBytes", summary.totalBytes());
        out.put("ndjsonPathAggregateHash12", summary.pathAggregateHash12());
        return out;
    }

    private Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        int configured = 0;
        int secretConfigured = 0;
        int missing = 0;
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String key : CONFIG_KEYS) {
            String value = property(key);
            boolean secretLike = isSecretKey(key);
            boolean isMissing = ConfigValueGuards.isMissing(value);
            if (isMissing) {
                missing++;
            } else {
                configured++;
                if (secretLike) {
                    secretConfigured++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configured", !isMissing);
            row.put("placeholderOrMissing", isMissing);
            row.put("secretLike", secretLike);
            if (!isMissing && !secretLike) {
                row.put("valueHash12", SafeRedactor.hash12(value));
            }
            keys.put(key, row);
        }
        out.put("configuredCount", configured);
        out.put("missingCount", missing);
        out.put("secretConfiguredCount", secretConfigured);
        out.put("keys", keys);
        return out;
    }

    private static Map<String, Object> runtime(Path base) {
        Path tools = base.resolve(Path.of("main", "resources", "mcp", "awx-control-tower-tools.json"));
        String manifest = readSmall(tools, 256_000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlTowerToolsPresent", Files.isRegularFile(tools));
        out.put("agentDbSnapshotDeclared", manifest.contains("agent_db_snapshot"));
        out.put("supabaseContextProbeDeclared", manifest.contains("supabase_context_probe"));
        out.put("toolManifestHash12", fileHash12(tools));
        out.put("runtimeSnapshotArtifactCount", countFiles(base.resolve(Path.of("data", "agent-handoff")), 8,
                List.of(".json", ".jsonl", ".ndjson")));
        return out;
    }

    private Map<String, Object> supabase(Path base) {
        Path clientSample = base.resolve(Path.of("main", "resources", "mcp", "awx-control-tower-mcp-client.sample.json"));
        Path snapshot = base.resolve(Path.of("data", "db-gap-report", "supabase-schema-snapshot.json"));
        Map<String, Object> out = new LinkedHashMap<>();
        int configured = 0;
        int secretConfigured = 0;
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String key : SUPABASE_KEYS) {
            String value = property(key);
            boolean secretLike = isSecretKey(key);
            boolean present = !ConfigValueGuards.isMissing(value);
            if (present) {
                configured++;
                if (secretLike) {
                    secretConfigured++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configured", present);
            row.put("secretLike", secretLike);
            if (present && !secretLike) {
                row.put("valueHash12", SafeRedactor.hash12(value));
            }
            keys.put(key, row);
        }
        out.put("configuredEnvCount", configured);
        out.put("secretEnvConfiguredCount", secretConfigured);
        out.put("mcpClientSamplePresent", Files.isRegularFile(clientSample));
        out.put("schemaSnapshotPresent", Files.isRegularFile(snapshot));
        out.put("schemaSnapshotBytes", size(snapshot));
        out.put("schemaSnapshotHash12", fileHash12(snapshot));
        out.put("schemaSnapshotSummary", supabaseSnapshotSummary(snapshot));
        out.put("ddlReadiness", supabaseDdlReadiness(base));
        out.put("remoteSqlEvidenceNeeded", true);
        out.put("keys", keys);
        return out;
    }

    private static Map<String, Object> supabaseSnapshotSummary(Path snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", Files.isRegularFile(snapshot));
        if (!Files.isRegularFile(snapshot)) {
            out.put("parseable", false);
            return out;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(snapshot, StandardCharsets.UTF_8));
            JsonNode snapshotImport = root.path("snapshotImport");
            JsonNode mcp = root.path("mcp");
            JsonNode mcpConfig = root.path("mcpConfig");
            JsonNode advisors = root.path("advisors");

            out.put("parseable", true);
            out.put("schemaVersion", safeJsonLabel(root.path("schemaVersion").asText("")));
            out.put("decision", safeJsonLabel(root.path("decision").asText("")));
            out.put("mutationAllowed", root.path("mutationAllowed").asBoolean(false));
            out.put("evidenceNeededCount", root.path("evidence_needed").isArray()
                    ? root.path("evidence_needed").size() : 0);
            out.put("nextActionCount", root.path("nextActions").isArray()
                    ? root.path("nextActions").size() : 0);
            out.put("mcpReachable", mcp.path("reachable").asBoolean(false));
            out.put("mcpHttpStatus", mcp.path("httpStatus").isNumber() ? mcp.path("httpStatus").asInt() : 0);
            out.put("mcpUnauthenticatedExpected", mcp.path("unauthenticatedExpected").asBoolean(false));
            out.put("mcpReadOnlyMode", mcpConfig.path("readOnlyMode").asBoolean(false));
            out.put("mcpRawSecretPatternHits", mcpConfig.path("rawSecretPatternHits").isNumber()
                    ? mcpConfig.path("rawSecretPatternHits").asInt() : 0);
            out.put("advisorAvailable", advisors.path("available").asBoolean(false));
            out.put("advisorRowCount", advisors.path("rows").isArray() ? advisors.path("rows").size() : 0);
            out.put("importedResultCount", snapshotImport.path("importedResultCount").isNumber()
                    ? snapshotImport.path("importedResultCount").asInt() : 0);
            out.put("missingResultCount", snapshotImport.path("missingResultCount").isNumber()
                    ? snapshotImport.path("missingResultCount").asInt() : 0);
            out.put("unexpectedResultCount", snapshotImport.path("unexpectedResultCount").isNumber()
                    ? snapshotImport.path("unexpectedResultCount").asInt() : 0);
            out.put("storedRawRows", snapshotImport.path("storedRawRows").asBoolean(false));
            out.put("missingResultNamesHash12", jsonArrayHash12(snapshotImport.path("missingResultNames")));
            return out;
        } catch (Exception ex) {
            traceSuppressed("supabase.snapshotSummary", snapshot, ex);
            out.put("parseable", false);
            return out;
        }
    }

    private static Map<String, Object> supabaseDdlReadiness(Path base) {
        Path ddlRoot = base.resolve(Path.of("main", "resources", "db", "ddl"));
        Path dbReadme = base.resolve(Path.of("main", "resources", "db", "README.md"));
        DdlPatternCounts counts = scanDdlPatterns(ddlRoot);
        String dialect = ddlDialect(dbReadme, counts);
        boolean dataApiApplicable = !"mysql-mariadb".equals(dialect)
                && counts.publicOrUnqualifiedCreateTableCount() > 0;
        long rlsEvidenceMissingCount = dataApiApplicable
                ? Math.max(0L, counts.publicOrUnqualifiedCreateTableCount() - counts.rowLevelSecurityEnabledCount())
                : 0L;
        boolean rlsEvidenceNeeded = dataApiApplicable && rlsEvidenceMissingCount > 0L;
        boolean grantEvidenceNeeded = dataApiApplicable
                && (counts.anonGrantCount() == 0 || counts.authenticatedGrantCount() == 0);
        boolean rlsPolicyEvidenceNeeded = dataApiApplicable && counts.rowLevelSecurityPolicyCount() == 0L;
        boolean securityDefinerReviewNeeded = !"mysql-mariadb".equals(dialect)
                && counts.publicSecurityDefinerFunctionCount() > 0L;
        long publicViewSecurityInvokerMissingCount = "mysql-mariadb".equals(dialect)
                ? 0L
                : Math.max(0L, counts.publicOrUnqualifiedCreateViewCount() - counts.securityInvokerViewCount());
        boolean publicViewSecurityInvokerReviewNeeded = publicViewSecurityInvokerMissingCount > 0L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ddlRootPresent", Files.isDirectory(ddlRoot));
        out.put("ddlDialect", dialect);
        out.put("mysqlMariaDbDdl", "mysql-mariadb".equals(dialect));
        out.put("postgresSupabaseDdl", "postgres-supabase".equals(dialect));
        out.put("ddlFileCount", counts.ddlFileCount());
        out.put("createTableCount", counts.createTableCount());
        out.put("publicOrUnqualifiedCreateTableCount", counts.publicOrUnqualifiedCreateTableCount());
        out.put("createViewCount", counts.createViewCount());
        out.put("publicOrUnqualifiedCreateViewCount", counts.publicOrUnqualifiedCreateViewCount());
        out.put("securityInvokerViewCount", counts.securityInvokerViewCount());
        out.put("publicViewSecurityInvokerMissingCount", publicViewSecurityInvokerMissingCount);
        out.put("publicViewSecurityInvokerReviewNeeded", publicViewSecurityInvokerReviewNeeded);
        out.put("rowLevelSecurityEnabledCount", counts.rowLevelSecurityEnabledCount());
        out.put("anonGrantCount", counts.anonGrantCount());
        out.put("authenticatedGrantCount", counts.authenticatedGrantCount());
        out.put("publicSecurityDefinerFunctionCount", counts.publicSecurityDefinerFunctionCount());
        out.put("securityDefinerReviewNeeded", securityDefinerReviewNeeded);
        out.put("rlsPolicyCount", counts.rowLevelSecurityPolicyCount());
        out.put("dataApiGrantEvidenceApplicable", dataApiApplicable);
        out.put("dataApiGrantEvidencePresent", counts.anonGrantCount() > 0 || counts.authenticatedGrantCount() > 0);
        out.put("rlsEvidenceMissingCount", rlsEvidenceMissingCount);
        out.put("rlsEvidencePartial", dataApiApplicable
                && counts.rowLevelSecurityEnabledCount() > 0
                && counts.rowLevelSecurityEnabledCount() < counts.publicOrUnqualifiedCreateTableCount());
        out.put("rlsEvidenceNeeded", rlsEvidenceNeeded);
        out.put("rlsPolicyEvidenceNeeded", rlsPolicyEvidenceNeeded);
        out.put("evidenceNeeded", rlsEvidenceNeeded
                || grantEvidenceNeeded
                || rlsPolicyEvidenceNeeded
                || securityDefinerReviewNeeded
                || publicViewSecurityInvokerReviewNeeded);
        return out;
    }

    private Map<String, Object> postgres(Path base) {
        String checkpointUrl = property("rag.langgraph.postgres.url");
        String checkpointPassword = property("RAG_LANGGRAPH_POSTGRES_PASSWORD");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpointConfigPresent", !ConfigValueGuards.isMissing(checkpointUrl));
        out.put("passwordPlaceholderPresent", !ConfigValueGuards.isMissing(checkpointPassword));
        out.put("dependencyPresent", checkDependencyHint(base, List.of(
                "checkpoint-postgres",
                "langgraph-checkpoint-postgres",
                "langgraph4j-postgres-saver",
                "postgres-saver"
        )));
        out.put("postgresSaverSourceCount", countJavaSourcesContaining(base,
                List.of("PostgresSaver", "LangGraphPostgresCheckpoint", "CheckpointSaver")));
        return out;
    }

    private String property(String key) {
        if (environment == null || key == null) {
            return "";
        }
        String value = environment.getProperty(key);
        return value == null ? "" : value;
    }

    private static long countMatchingJavaFiles(Path root, Predicate<Path> predicate) {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(root, 18)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(predicate)
                    .count();
        } catch (IOException ex) {
            traceSuppressed("jpa.scan", root, ex);
            return 0L;
        }
    }

    private static long countFiles(Path root, int depth, List<String> suffixes) {
        return summarizeFiles(root, depth, suffixes).count();
    }

    private static FileSummary summarizeFiles(Path root, int depth, List<String> suffixes) {
        if (!Files.isDirectory(root)) {
            return new FileSummary(0L, 0L, null);
        }
        long count = 0L;
        long bytes = 0L;
        StringBuilder paths = new StringBuilder();
        try (Stream<Path> stream = Files.walk(root, depth)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> !isUnderGeneratedDir(root, path))
                    .filter(path -> suffixMatches(path, suffixes))
                    .sorted()
                    .toList();
            for (Path file : files) {
                count++;
                bytes += size(file);
                paths.append(root.relativize(file)).append('|');
            }
            return new FileSummary(count, bytes, SafeRedactor.hash12(paths.toString()));
        } catch (IOException ex) {
            traceSuppressed("file.scan", root, ex);
            return new FileSummary(count, bytes, SafeRedactor.hash12(paths.toString()));
        }
    }

    private static boolean suffixMatches(Path path, List<String> suffixes) {
        if (path == null || suffixes == null || suffixes.isEmpty()) {
            return true;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return suffixes.stream().anyMatch(name::endsWith);
    }

    private static boolean isUnderGeneratedDir(Path root, Path candidate) {
        try {
            Path relative = root.relativize(candidate);
            if (relative.getNameCount() == 0) {
                return false;
            }
            String firstSegment = relative.getName(0).toString().toLowerCase(Locale.ROOT);
            return GENERATED_DIR_EXCLUDES.contains(firstSegment);
        } catch (Exception ex) {
            traceSuppressed("file.generatedDirCheck", candidate, ex);
            return false;
        }
    }

    private static boolean containsEntityAnnotation(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return ENTITY_ANNOTATION_PATTERN.matcher(text).find();
        } catch (Exception ex) {
            traceSuppressed("file.containsEntity", path, ex);
            return false;
        }
    }

    private static boolean containsJpaRepositoryExtends(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return JPA_REPOSITORY_EXTENDS_PATTERN.matcher(text).find();
        } catch (Exception ex) {
            traceSuppressed("file.containsJpaRepo", path, ex);
            return false;
        }
    }

    private static boolean checkDependencyHint(Path base, List<String> artifacts) {
        Path buildFile = base.resolve("build.gradle.kts");
        if (!Files.isRegularFile(buildFile)) {
            buildFile = base.resolve("build.gradle");
        }
        if (!Files.isRegularFile(buildFile)) {
            return false;
        }
        try {
            String content = Files.readString(buildFile, StandardCharsets.UTF_8);
            return artifacts.stream().anyMatch(content::contains);
        } catch (Exception ex) {
            traceSuppressed("dependency.check", buildFile, ex);
            return false;
        }
    }

    private static long countJavaSourcesContaining(Path base, List<String> needles) {
        Path javaRoot = base.resolve(Path.of("main", "java"));
        if (!Files.isDirectory(javaRoot)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(javaRoot, 18)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> needles.stream().anyMatch(needle -> containsLiteral(path, needle)))
                    .count();
        } catch (IOException ex) {
            traceSuppressed("source.scan.needles", javaRoot, ex);
            return 0L;
        }
    }

    private static boolean containsLiteral(Path path, String needle) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.contains(needle);
        } catch (Exception ex) {
            traceSuppressed("file.containsLiteral", path, ex);
            return false;
        }
    }

    private static DdlPatternCounts scanDdlPatterns(Path ddlRoot) {
        if (!Files.isDirectory(ddlRoot)) {
            return new DdlPatternCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        long ddlFileCount = 0L;
        long createTableCount = 0L;
        long publicOrUnqualifiedCreateTableCount = 0L;
        long createViewCount = 0L;
        long publicOrUnqualifiedCreateViewCount = 0L;
        long securityInvokerViewCount = 0L;
        long rowLevelSecurityEnabledCount = 0L;
        long anonGrantCount = 0L;
        long authenticatedGrantCount = 0L;
        long publicSecurityDefinerFunctionCount = 0L;
        long rowLevelSecurityPolicyCount = 0L;
        long mysqlHintCount = 0L;
        long postgresSupabaseHintCount = 0L;
        try (Stream<Path> stream = Files.walk(ddlRoot, 4)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> !isUnderGeneratedDir(ddlRoot, path))
                    .filter(path -> suffixMatches(path, List.of(".sql")))
                    .sorted()
                    .toList();
            for (Path file : files) {
                ddlFileCount++;
                String sql = readSmall(file, 512_000);
                DdlTableCounts tableCounts = countCreateTableDeclarations(sql);
                createTableCount += tableCounts.createTableCount();
                publicOrUnqualifiedCreateTableCount += tableCounts.publicOrUnqualifiedCreateTableCount();
                DdlViewCounts viewCounts = countCreateViewDeclarations(sql);
                createViewCount += viewCounts.createViewCount();
                publicOrUnqualifiedCreateViewCount += viewCounts.publicOrUnqualifiedCreateViewCount();
                securityInvokerViewCount += viewCounts.securityInvokerViewCount();
                rowLevelSecurityEnabledCount += countPatternMatches(sql, ROW_LEVEL_SECURITY_ENABLE_PATTERN);
                anonGrantCount += countPatternMatches(sql, ANON_GRANT_PATTERN);
                authenticatedGrantCount += countPatternMatches(sql, AUTHENTICATED_GRANT_PATTERN);
                publicSecurityDefinerFunctionCount += countPatternMatches(sql, PUBLIC_SECURITY_DEFINER_FUNCTION_PATTERN);
                rowLevelSecurityPolicyCount += countPatternMatches(sql, ROW_LEVEL_SECURITY_POLICY_PATTERN);
                mysqlHintCount += countPatternMatches(sql, MYSQL_DDL_HINT_PATTERN);
                postgresSupabaseHintCount += countPatternMatches(sql, POSTGRES_SUPABASE_DDL_HINT_PATTERN);
            }
        } catch (IOException ex) {
            traceSuppressed("supabase.ddlReadiness", ddlRoot, ex);
        }
        return new DdlPatternCounts(
                ddlFileCount,
                createTableCount,
                publicOrUnqualifiedCreateTableCount,
                createViewCount,
                publicOrUnqualifiedCreateViewCount,
                securityInvokerViewCount,
                rowLevelSecurityEnabledCount,
                anonGrantCount,
                authenticatedGrantCount,
                publicSecurityDefinerFunctionCount,
                rowLevelSecurityPolicyCount,
                mysqlHintCount,
                postgresSupabaseHintCount);
    }

    private static String ddlDialect(Path dbReadme, DdlPatternCounts counts) {
        String notes = readSmall(dbReadme, 64_000).toLowerCase(Locale.ROOT);
        if (notes.contains("mysql") || notes.contains("mariadb")) {
            return "mysql-mariadb";
        }
        if (counts.postgresSupabaseHintCount() > 0 && counts.mysqlHintCount() == 0) {
            return "postgres-supabase";
        }
        if (counts.mysqlHintCount() > 0 && counts.postgresSupabaseHintCount() == 0) {
            return "mysql-mariadb";
        }
        if (counts.postgresSupabaseHintCount() > counts.mysqlHintCount()) {
            return "postgres-supabase";
        }
        return "unknown";
    }

    private static DdlTableCounts countCreateTableDeclarations(String sql) {
        long createTableCount = 0L;
        long publicOrUnqualifiedCreateTableCount = 0L;
        Matcher matcher = CREATE_TABLE_DECLARATION_PATTERN.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            createTableCount++;
            String schema = matcher.group(1);
            if (schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema)) {
                publicOrUnqualifiedCreateTableCount++;
            }
        }
        return new DdlTableCounts(createTableCount, publicOrUnqualifiedCreateTableCount);
    }

    private static DdlViewCounts countCreateViewDeclarations(String sql) {
        String text = sql == null ? "" : sql;
        long createViewCount = 0L;
        long publicOrUnqualifiedCreateViewCount = 0L;
        long securityInvokerViewCount = 0L;
        Matcher matcher = CREATE_VIEW_DECLARATION_PATTERN.matcher(text);
        while (matcher.find()) {
            createViewCount++;
            String schema = matcher.group(1);
            boolean publicOrUnqualified = schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema);
            if (publicOrUnqualified) {
                publicOrUnqualifiedCreateViewCount++;
                String statement = text.substring(matcher.start(), statementEnd(text, matcher.end()));
                if (SECURITY_INVOKER_TRUE_PATTERN.matcher(statement).find()) {
                    securityInvokerViewCount++;
                }
            }
        }
        return new DdlViewCounts(createViewCount, publicOrUnqualifiedCreateViewCount, securityInvokerViewCount);
    }

    private static int statementEnd(String text, int from) {
        int semi = text.indexOf(';', Math.max(0, from));
        return semi < 0 ? text.length() : semi + 1;
    }

    private static long countPatternMatches(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        return pattern.matcher(text).results().count();
    }

    private static String safeJsonLabel(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    private static String jsonArrayHash12(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        StringBuilder values = new StringBuilder();
        for (JsonNode item : node) {
            values.append(safeJsonLabel(item.asText(""))).append('|');
        }
        return SafeRedactor.hash12(values.toString());
    }

    private static String readSmall(Path path, int maxBytes) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            int length = Math.min(bytes.length, Math.max(0, maxBytes));
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            traceSuppressed("file.readSmall", path, ex);
            return "";
        }
    }

    private static long size(Path path) {
        if (!Files.isRegularFile(path)) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (IOException ex) {
            traceSuppressed("file.size", path, ex);
            return 0L;
        }
    }

    private static String fileHash12(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder out = new StringBuilder(12);
            for (byte b : hash) {
                if (out.length() >= 12) {
                    break;
                }
                out.append(String.format(Locale.ROOT, "%02x", b));
            }
            return out.substring(0, Math.min(12, out.length()));
        } catch (Exception ex) {
            traceSuppressed("file.hash", path, ex);
            return null;
        }
    }

    private static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(".", "");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("service") && normalized.contains("key")
                || normalized.contains("anonkey")
                || normalized.contains("dburl")
                || normalized.contains("datasourceurl");
    }

    private static void traceSuppressed(String stage, Path path, Throwable error) {
        String rawPath = path == null ? null : path.toString();
        TraceStore.put("agent.ops.dbEvidenceScan.suppressed", true);
        TraceStore.put("agent.ops.dbEvidenceScan.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.ops.dbEvidenceScan.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.ops.dbEvidenceScan.suppressed.pathHash", SafeRedactor.hashValue(rawPath));
        TraceStore.put("agent.ops.dbEvidenceScan.suppressed.pathLength", rawPath == null ? 0 : rawPath.length());
    }

    private record FileSummary(long count, long totalBytes, String pathAggregateHash12) {
    }

    private record DdlTableCounts(long createTableCount, long publicOrUnqualifiedCreateTableCount) {
    }

    private record DdlViewCounts(
            long createViewCount,
            long publicOrUnqualifiedCreateViewCount,
            long securityInvokerViewCount) {
    }

    private record DdlPatternCounts(
            long ddlFileCount,
            long createTableCount,
            long publicOrUnqualifiedCreateTableCount,
            long createViewCount,
            long publicOrUnqualifiedCreateViewCount,
            long securityInvokerViewCount,
            long rowLevelSecurityEnabledCount,
            long anonGrantCount,
            long authenticatedGrantCount,
            long publicSecurityDefinerFunctionCount,
            long rowLevelSecurityPolicyCount,
            long mysqlHintCount,
            long postgresSupabaseHintCount) {
    }
}
