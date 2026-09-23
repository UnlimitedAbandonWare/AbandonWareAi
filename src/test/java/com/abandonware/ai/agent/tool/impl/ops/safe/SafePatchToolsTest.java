package com.abandonware.ai.agent.tool.impl.ops.safe;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafePatchToolsTest {

    @TempDir
    Path root;

    @Test
    void dbEvidenceScanReportsDbSignalsWithoutRowsSecretsOrRawPaths() throws Exception {
        write("main/java/com/example/lms/domain/Course.java",
                "package com.example.lms.domain;\n" +
                        "import jakarta.persistence.Entity;\n" +
                        "@Entity\n" +
                        "class Course {}\n");
        write("main/java/com/example/lms/repository/CourseRepository.java",
                "package com.example.lms.repository;\n" +
                        "import com.example.lms.domain.Course;\n" +
                        "import org.springframework.data.jpa.repository.JpaRepository;\n" +
                        "interface CourseRepository extends JpaRepository<Course, Long> {}\n");
        write("main/resources/application-local.yml",
                        "spring:\n" +
                        "  datasource:\n" +
                        "    url: jdbc:mysql://db-secret-host.example/lms\n" +
                        "    password: redacted-local-test-secret-value\n" +
                        "  jpa:\n" +
                        "    hibernate:\n" +
                        "      ddl-auto: validate\n");
        write("data/db-gap-report/gap_matrix.json",
                "{\"table\":\"students\",\"rawRow\":\"student@example.com\",\"token\":\"redacted-gap-secret-value\"}\n");
        write("data/agent-handoff/codex/cycles.jsonl",
                "{\"cycle\":1,\"rawRow\":\"private row\",\"secret\":\"redacted-jsonl-secret-value\"}\n");
        write("main/resources/mcp/awx-control-tower-tools.json",
                "{\"tools\":[{\"name\":\"supabase_context_probe\"},{\"name\":\"agent_db_snapshot\"}]}\n");

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://db-secret-host.example/lms")
                .withProperty("spring.datasource.password", "redacted-env-secret-value")
                .withProperty("SUPABASE_ACCESS_TOKEN", "sbp_" + "fake_secret_token")
                .withProperty("SUPABASE_PROJECT_REF", "project-secret-ref");

        DbEvidenceScanTool tool = new DbEvidenceScanTool(root, environment);
        ToolResponse response = tool.execute(new ToolRequest(Map.of(), null));
        Map<String, Object> scan = castMap(response.data().get("dbEvidenceScan"));

        assertEquals("db_evidence_scan", tool.id());
        assertEquals("agent.db_evidence_scan.v1", scan.get("schemaVersion"));
        assertTrue(scan.toString().contains("entityCount=1"));
        assertTrue(scan.toString().contains("repositoryCount=1"));
        assertTrue(scan.toString().contains("gapReportFileCount=1"));
        assertTrue(scan.toString().contains("ndjsonFileCount=1"));
        assertTrue(scan.toString().contains("supabaseContextProbeDeclared=true"));
        assertTrue(scan.toString().contains("agentDbSnapshotDeclared=true"));

        String rendered = response.data().toString();
        assertFalse(rendered.contains("student@example.com"));
        assertFalse(rendered.contains("private row"));
        assertFalse(rendered.contains("redacted-local-test-secret-value"));
        assertFalse(rendered.contains("redacted-gap-secret-value"));
        assertFalse(rendered.contains("redacted-jsonl-secret-value"));
        assertFalse(rendered.contains("redacted-env-secret-value"));
        assertFalse(rendered.contains("db-secret-host.example"));
        assertFalse(rendered.contains(root.toString()));
    }

    @Test
    void dbEvidenceScanCountsJpaOnlyFromRealAnnotationsAndRepositoryDeclarations() throws Exception {
        write("main/java/com/example/tools/DbEvidenceScanTool.java",
                "package com.example.tools;\n" +
                        "class DbEvidenceScanTool {\n" +
                        "  private static final String ENTITY_MARKER = \"@\" + \"Entity\";\n" +
                        "  private static final String REPOSITORY_MARKER = \"Jpa\" + \"Repository\";\n" +
                        "}\n");
        write("main/java/com/example/lms/repository/ReadmeOnly.java",
                "package com.example.lms.repository;\n" +
                        "class ReadmeOnly {\n" +
                        "  String text = \"JpaRepository is mentioned here but not extended\";\n" +
                        "}\n");
        write("main/java/com/example/lms/repository/CourseRepository.java",
                "package com.example.lms.repository;\n" +
                        "import com.example.lms.domain.Course;\n" +
                        "import org.springframework.data.jpa.repository.JpaRepository;\n" +
                        "interface CourseRepository extends JpaRepository<Course, Long> {}\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> jpa = castMap(scan.get("jpa"));

        assertEquals(0L, jpa.get("entityCount"));
        assertEquals(1L, jpa.get("repositoryCount"));
    }

    @Test
    void dbEvidenceScanExcludesGeneratedDirectoriesFromFileSummaries() throws Exception {
        write("main/java/build/generated/GeneratedEntity.java",
                "package build.generated;\n" +
                        "import jakarta.persistence.Entity;\n" +
                        "@Entity\n" +
                        "class GeneratedEntity {}\n");
        write("main/java/com/example/lms/domain/Course.java",
                "package com.example.lms.domain;\n" +
                        "class Course {}\n");
        write("data/build/generated.ndjson", "{\"generated\":true}\n");
        write("data/agent-handoff/codex/cycles.ndjson", "{\"cycle\":1}\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> jpa = castMap(scan.get("jpa"));
        Map<String, Object> ndjson = castMap(scan.get("ndjson"));

        assertEquals(1L, jpa.get("javaFileCount"));
        assertEquals(1L, ndjson.get("ndjsonFileCount"));
    }

    @Test
    void dbEvidenceScanReportsPostgresCheckpointEvidenceWithoutValues() throws Exception {
        write("build.gradle.kts",
                "dependencies {\n" +
                        "  implementation(\"org.bsc.langgraph4j:langgraph4j-postgres-saver\")\n" +
                        "}\n");
        write("main/java/com/example/lms/checkpoint/PostgresCheckpointConfig.java",
                "package com.example.lms.checkpoint;\n" +
                        "class PostgresCheckpointConfig {\n" +
                        "  String saver = \"PostgresSaver\";\n" +
                        "}\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("rag.langgraph.postgres.url", "jdbc:postgresql://secret-postgres-host.example/rag")
                .withProperty("RAG_LANGGRAPH_POSTGRES_PASSWORD", "redacted-postgres-password-value");

        DbEvidenceScanTool tool = new DbEvidenceScanTool(root, environment);
        ToolResponse response = tool.execute(new ToolRequest(Map.of(), null));
        Map<String, Object> scan = castMap(response.data().get("dbEvidenceScan"));
        Map<String, Object> postgres = castMap(scan.get("postgres"));

        assertEquals(true, postgres.get("checkpointConfigPresent"));
        assertEquals(true, postgres.get("passwordPlaceholderPresent"));
        assertEquals(true, postgres.get("dependencyPresent"));
        assertEquals(1L, postgres.get("postgresSaverSourceCount"));

        String rendered = response.data().toString();
        assertFalse(rendered.contains("secret-postgres-host.example"));
        assertFalse(rendered.contains("redacted-postgres-password-value"));
    }

    @Test
    void dbEvidenceScanReportsSupabaseDdlReadinessWithoutSqlIdentifiers() throws Exception {
        write("data/db-gap-report/supabase-schema-snapshot.json",
                "{\n" +
                        "  \"schemaVersion\":\"awx.mcp.supabase_schema_snapshot.v1\",\n" +
                        "  \"decision\":\"supabase_schema_snapshot_evidence_needed\",\n" +
                        "  \"mutationAllowed\":false,\n" +
                        "  \"evidence_needed\":[\"auth required\",\"missing results\"],\n" +
                        "  \"nextActions\":[\"complete_oauth\",\"run_readonly_sql\"],\n" +
                        "  \"mcp\":{\"reachable\":true,\"httpStatus\":403,\"unauthenticatedExpected\":true},\n" +
                        "  \"mcpConfig\":{\"readOnlyMode\":true,\"rawSecretPatternHits\":0},\n" +
                        "  \"advisors\":{\"available\":false,\"rows\":[]},\n" +
                        "  \"snapshotImport\":{\"importedResultCount\":0,\"missingResultCount\":2,\"missingResultNames\":[\"rls_and_table_flags\",\"data_api_role_grants\"],\"unexpectedResultCount\":0,\"storedRawRows\":false}\n" +
                        "}\n");
        write("main/resources/db/ddl/V20260616__supabase_public_tables.sql",
                "create table public.agent_private_notes (id bigint primary key);\n" +
                        "create table if not exists learning_signal (id bigint primary key);\n" +
                        "alter table public.agent_private_notes enable row level security;\n" +
                        "grant select on table public.agent_private_notes to authenticated;\n" +
                        "grant select on all tables in schema public to anon;\n" +
                        "create function public.leaky_fn() returns void language sql security definer as $$ select 1 $$;\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> snapshotSummary = castMap(supabase.get("schemaSnapshotSummary"));
        Map<String, Object> readiness = castMap(supabase.get("ddlReadiness"));

        assertEquals(true, snapshotSummary.get("parseable"));
        assertEquals("supabase_schema_snapshot_evidence_needed", snapshotSummary.get("decision"));
        assertEquals(false, snapshotSummary.get("mutationAllowed"));
        assertEquals(2, snapshotSummary.get("evidenceNeededCount"));
        assertEquals(2, snapshotSummary.get("nextActionCount"));
        assertEquals(true, snapshotSummary.get("mcpReachable"));
        assertEquals(403, snapshotSummary.get("mcpHttpStatus"));
        assertEquals(true, snapshotSummary.get("mcpUnauthenticatedExpected"));
        assertEquals(true, snapshotSummary.get("mcpReadOnlyMode"));
        assertEquals(0, snapshotSummary.get("mcpRawSecretPatternHits"));
        assertEquals(0, snapshotSummary.get("importedResultCount"));
        assertEquals(2, snapshotSummary.get("missingResultCount"));
        assertEquals(false, snapshotSummary.get("storedRawRows"));
        assertTrue(String.valueOf(snapshotSummary.get("missingResultNamesHash12")).length() > 0);
        assertEquals(1L, readiness.get("ddlFileCount"));
        assertEquals(2L, readiness.get("createTableCount"));
        assertEquals(2L, readiness.get("publicOrUnqualifiedCreateTableCount"));
        assertEquals(1L, readiness.get("rowLevelSecurityEnabledCount"));
        assertEquals(1L, readiness.get("anonGrantCount"));
        assertEquals(1L, readiness.get("authenticatedGrantCount"));
        assertEquals(1L, readiness.get("publicSecurityDefinerFunctionCount"));
        assertEquals(true, readiness.get("dataApiGrantEvidencePresent"));
        assertEquals(true, readiness.get("rlsEvidencePartial"));

        String rendered = scan.toString();
        assertFalse(rendered.contains("agent_private_notes"));
        assertFalse(rendered.contains("learning_signal"));
        assertFalse(rendered.contains("leaky_fn"));
        assertFalse(rendered.contains("select 1"));
        assertFalse(rendered.contains("rls_and_table_flags"));
        assertFalse(rendered.contains("data_api_role_grants"));
    }

    @Test
    void dbEvidenceScanRecognizesCurrentSupabaseApiKeyEnvNamesWithoutValues() {
        String publishableKey = "sb_publishable_" + "unit_test_public";
        String publishableKeys = "{\"default\":\"sb_publishable_" + "unit_test_json\"}";
        String nextPublicPublishableKey = "sb_publishable_" + "unit_test_next";
        String secretKey = "sb_secret_" + "unit_test_backend";
        String secretKeys = "{\"default\":\"sb_secret_" + "unit_test_json\"}";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SUPABASE_PUBLISHABLE_KEY", publishableKey)
                .withProperty("SUPABASE_PUBLISHABLE_KEYS", publishableKeys)
                .withProperty("NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY", nextPublicPublishableKey)
                .withProperty("SUPABASE_SECRET_KEY", secretKey)
                .withProperty("SUPABASE_SECRET_KEYS", secretKeys)
                .withProperty("SUPABASE_ANON_KEY", "legacy-anon-unit-test-value");

        DbEvidenceScanTool tool = new DbEvidenceScanTool(root, environment);
        ToolResponse response = tool.execute(new ToolRequest(Map.of(), null));
        Map<String, Object> scan = castMap(response.data().get("dbEvidenceScan"));
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> keys = castMap(supabase.get("keys"));

        assertEquals(6, supabase.get("configuredEnvCount"));
        assertEquals(3, supabase.get("secretEnvConfiguredCount"));
        assertEquals(true, castMap(keys.get("SUPABASE_PUBLISHABLE_KEY")).get("configured"));
        assertEquals(true, castMap(keys.get("SUPABASE_PUBLISHABLE_KEYS")).get("configured"));
        assertEquals(true, castMap(keys.get("NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY")).get("configured"));
        assertEquals(true, castMap(keys.get("SUPABASE_SECRET_KEY")).get("secretLike"));
        assertEquals(true, castMap(keys.get("SUPABASE_SECRET_KEYS")).get("secretLike"));
        assertEquals(true, castMap(keys.get("SUPABASE_ANON_KEY")).get("secretLike"));

        String rendered = response.data().toString();
        assertFalse(rendered.contains("sb_publishable_unit_test"));
        assertFalse(rendered.contains("sb_secret_unit_test"));
        assertFalse(rendered.contains("legacy-anon-unit-test-value"));
    }

    @Test
    void dbEvidenceScanMalformedSupabaseSnapshotFailsSoftWithoutRawJsonLeak() throws Exception {
        TraceStore.clear();
        write("data/db-gap-report/supabase-schema-snapshot.json",
                "{\"schemaVersion\":\"awx.mcp.supabase_schema_snapshot.v1\",\"raw\":\"ownerToken=redacted-test-token\"");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> snapshotSummary = castMap(supabase.get("schemaSnapshotSummary"));

        assertEquals(true, snapshotSummary.get("present"));
        assertEquals(false, snapshotSummary.get("parseable"));
        assertEquals(true, TraceStore.get("agent.ops.dbEvidenceScan.suppressed"));
        assertEquals("supabase.snapshotSummary",
                TraceStore.get("agent.ops.dbEvidenceScan.suppressed.stage"));
        assertTrue(String.valueOf(TraceStore.get("agent.ops.dbEvidenceScan.suppressed.errorType")).endsWith("Exception"));
        String rendered = scan.toString() + TraceStore.getAll();
        assertFalse(rendered.contains("ownerToken=redacted-test-token"));
        assertFalse(rendered.contains("awx.mcp.supabase_schema_snapshot.v1"));
        TraceStore.clear();
    }

    @Test
    void dbEvidenceScanRequiresRlsEvidenceForSupabasePublicTablesEvenWhenGrantsExist() throws Exception {
        write("main/resources/db/ddl/V20260616__partial_supabase_rls.sql",
                "create table public.agent_private_notes (id bigint primary key);\n" +
                        "create table public.agent_private_events (id bigint primary key);\n" +
                        "alter table public.agent_private_notes enable row level security;\n" +
                        "grant select on all tables in schema public to anon;\n" +
                        "grant select on table public.agent_private_notes to authenticated;\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> readiness = castMap(supabase.get("ddlReadiness"));

        assertEquals(2L, readiness.get("publicOrUnqualifiedCreateTableCount"));
        assertEquals(1L, readiness.get("rowLevelSecurityEnabledCount"));
        assertEquals(true, readiness.get("rlsEvidencePartial"));
        assertEquals(1L, readiness.get("rlsEvidenceMissingCount"));
        assertEquals(true, readiness.get("rlsEvidenceNeeded"));
        assertEquals(true, readiness.get("evidenceNeeded"));

        String rendered = scan.toString();
        assertFalse(rendered.contains("agent_private_notes"));
        assertFalse(rendered.contains("agent_private_events"));
    }

    @Test
    void dbEvidenceScanRequiresRlsPolicyEvidenceForSupabasePublicTables() throws Exception {
        write("main/resources/db/ddl/V20260616__supabase_rls_without_policy.sql",
                "create table public.agent_private_notes (id bigint primary key);\n" +
                        "alter table public.agent_private_notes enable row level security;\n" +
                        "grant select on table public.agent_private_notes to anon;\n" +
                        "grant select on table public.agent_private_notes to authenticated;\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> readiness = castMap(supabase.get("ddlReadiness"));

        assertEquals(1L, readiness.get("publicOrUnqualifiedCreateTableCount"));
        assertEquals(1L, readiness.get("rowLevelSecurityEnabledCount"));
        assertEquals(0L, readiness.get("rlsPolicyCount"));
        assertEquals(true, readiness.get("rlsPolicyEvidenceNeeded"));
        assertEquals(true, readiness.get("evidenceNeeded"));

        String rendered = scan.toString();
        assertFalse(rendered.contains("agent_private_notes"));
    }

    @Test
    void dbEvidenceScanFlagsPublicSecurityDefinerFunctionsForSupabaseReview() throws Exception {
        write("main/resources/db/ddl/V20260616__supabase_security_definer.sql",
                "create table public.agent_private_notes (id bigint primary key, owner_id uuid);\n" +
                        "alter table public.agent_private_notes enable row level security;\n" +
                        "create policy agent_private_notes_owner_select on public.agent_private_notes\n" +
                        "  for select to authenticated using (owner_id = auth.uid());\n" +
                        "grant select on table public.agent_private_notes to anon;\n" +
                        "grant select on table public.agent_private_notes to authenticated;\n" +
                        "create function public.leaky_fn() returns void language sql security definer as $$ select 1 $$;\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> readiness = castMap(supabase.get("ddlReadiness"));

        assertEquals(1L, readiness.get("publicSecurityDefinerFunctionCount"));
        assertEquals(true, readiness.get("securityDefinerReviewNeeded"));
        assertEquals(true, readiness.get("evidenceNeeded"));

        String rendered = scan.toString();
        assertFalse(rendered.contains("agent_private_notes"));
        assertFalse(rendered.contains("leaky_fn"));
        assertFalse(rendered.contains("select 1"));
    }

    @Test
    void dbEvidenceScanFlagsPublicViewsWithoutSecurityInvokerForSupabaseReview() throws Exception {
        write("main/resources/db/ddl/V20260616__supabase_public_view.sql",
                "create table public.agent_private_notes (id bigint primary key, owner_id uuid);\n" +
                        "alter table public.agent_private_notes enable row level security;\n" +
                        "create policy agent_private_notes_owner_select on public.agent_private_notes\n" +
                        "  for select to authenticated using (owner_id = auth.uid());\n" +
                        "grant select on table public.agent_private_notes to anon;\n" +
                        "grant select on table public.agent_private_notes to authenticated;\n" +
                        "create view public.agent_private_notes_view as select id, owner_id from public.agent_private_notes;\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> readiness = castMap(supabase.get("ddlReadiness"));

        assertEquals(1L, readiness.get("publicOrUnqualifiedCreateViewCount"));
        assertEquals(0L, readiness.get("securityInvokerViewCount"));
        assertEquals(1L, readiness.get("publicViewSecurityInvokerMissingCount"));
        assertEquals(true, readiness.get("publicViewSecurityInvokerReviewNeeded"));
        assertEquals(true, readiness.get("evidenceNeeded"));

        String rendered = scan.toString();
        assertFalse(rendered.contains("agent_private_notes"));
        assertFalse(rendered.contains("agent_private_notes_view"));
        assertFalse(rendered.contains("owner_id"));
    }

    @Test
    void dbEvidenceScanDoesNotTreatMysqlDdlAsSupabaseDataApiGap() throws Exception {
        write("main/resources/db/README.md",
                "For production MySQL/MariaDB environments, use scripts under db/ddl/.\n");
        write("main/resources/db/ddl/V20260616__mysql_manual_table.sql",
                "CREATE TABLE IF NOT EXISTS rag_ops_ledger (\n" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT,\n" +
                        "  created_at DATETIME(6) NOT NULL,\n" +
                        "  PRIMARY KEY (id)\n" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        Map<String, Object> scan = executeScan();
        Map<String, Object> supabase = castMap(scan.get("supabase"));
        Map<String, Object> readiness = castMap(supabase.get("ddlReadiness"));

        assertEquals("mysql-mariadb", readiness.get("ddlDialect"));
        assertEquals(true, readiness.get("mysqlMariaDbDdl"));
        assertEquals(false, readiness.get("postgresSupabaseDdl"));
        assertEquals(1L, readiness.get("createTableCount"));
        assertEquals(false, readiness.get("dataApiGrantEvidenceApplicable"));
        assertEquals(false, readiness.get("evidenceNeeded"));
    }

    @Test
    void dbEvidenceScanGeneratedDirFailureLeavesRedactedBreadcrumb() throws Exception {
        TraceStore.clear();
        Method method = DbEvidenceScanTool.class.getDeclaredMethod("isUnderGeneratedDir", Path.class, Path.class);
        method.setAccessible(true);

        Object result = method.invoke(null, root, null);

        assertEquals(false, result);
        assertEquals(true, TraceStore.get("agent.ops.dbEvidenceScan.suppressed"));
        assertEquals("file.generatedDirCheck",
                TraceStore.get("agent.ops.dbEvidenceScan.suppressed.stage"));
        assertEquals("NullPointerException",
                TraceStore.get("agent.ops.dbEvidenceScan.suppressed.errorType"));
        assertEquals(0, TraceStore.get("agent.ops.dbEvidenceScan.suppressed.pathLength"));
        TraceStore.clear();
    }

    private Map<String, Object> executeScan() {
        DbEvidenceScanTool tool = new DbEvidenceScanTool(root, new MockEnvironment());
        ToolResponse response = tool.execute(new ToolRequest(Map.of(), null));
        return castMap(response.data().get("dbEvidenceScan"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void write(String relative, String text) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }
}
