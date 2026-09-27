import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class DesktopMariaDbMetadataSnapshot {
    private static final String SCHEMA_VERSION = "awx.desktop.mariadb-metadata.v1";
    private static final int EXIT_CONNECTED = 0;
    private static final int EXIT_CONFIG = 2;
    private static final int EXIT_CONNECT = 3;
    private static final int EXIT_QUERY = 4;
    private static final int EXIT_INTERNAL = 5;
    private static final int MAX_METADATA_TEXT_LENGTH = 512;

    private static final String READ_ONLY_SQL = "SET SESSION TRANSACTION READ ONLY";
    private static final String SERVER_VARIABLES_SQL =
            "SELECT @@version AS server_version, @@version_comment AS server_product";
    private static final String SERVER_STATUS_SQL =
            "SELECT @@session.tx_read_only AS read_only_session";
    private static final String TABLE_DETAILS_SQL =
            "SELECT table_name, table_type, engine, table_rows, data_length, index_length "
                    + "FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
    private static final String VIEWS_SQL =
            "SELECT table_name, check_option, is_updatable, security_type "
                    + "FROM information_schema.views WHERE table_schema = ? ORDER BY table_name";
    private static final String STRUCTURAL_TABLES_SQL =
            "SELECT table_name, table_type, engine, table_collation "
                    + "FROM information_schema.tables WHERE table_schema = ? "
                    + "ORDER BY BINARY table_name, BINARY table_type";
    private static final String STRUCTURAL_COLUMNS_SQL =
            "SELECT table_name, column_name, ordinal_position, column_type, is_nullable, "
                    + "character_set_name, collation_name, extra "
                    + "FROM information_schema.columns WHERE table_schema = ? "
                    + "ORDER BY BINARY table_name, ordinal_position, BINARY column_name";
    private static final String STRUCTURAL_INDEXES_SQL =
            "SELECT table_name, index_name, non_unique, seq_in_index, column_name, "
                    + "collation, index_type, sub_part "
                    + "FROM information_schema.statistics WHERE table_schema = ? "
                    + "ORDER BY BINARY table_name, BINARY index_name, seq_in_index, "
                    + "BINARY column_name";

    private static final Set<String> SYSTEM_CATALOGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "mysql", "information_schema", "performance_schema", "sys")));

    private static final List<String> ALLOWED_QUERY_IDS = Collections.unmodifiableList(Arrays.asList(
            "session_read_only",
            "server_variables",
            "server_status",
            "schemas",
            "tables",
            "columns",
            "indexes",
            "views",
            "metadata_fingerprint_start",
            "metadata_fingerprint_end"));

    private DesktopMariaDbMetadataSnapshot() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                Map<String, Object> out = syntheticSelfTest();
                System.out.print(Json.write(out));
                return;
            }
            if (args.length != 0) {
                emitAndExit(Result.failure("input_failure", EXIT_CONFIG,
                        "unsupported-arguments", "ArgumentFailure"));
                return;
            }

            Config config;
            try {
                config = Config.fromEnvironment();
            } catch (ConfigFailure failure) {
                emitAndExit(Result.failure("input_failure", EXIT_CONFIG,
                        failure.reasonCode, failure.getClass().getSimpleName()));
                return;
            }

            Result result = collect(config);
            System.out.print(Json.write(result.toMap()));
            if (!"connected".equals(result.decision)) {
                System.exit(result.exitCode);
            }
        } catch (Throwable failure) {
            emitAndExit(Result.failure("internal_contract_failure", EXIT_INTERNAL,
                    "internal-contract-failure", simpleClassName(failure)));
        }
    }

    private static void emitAndExit(Result result) {
        try {
            System.out.print(Json.write(result.toMap()));
        } catch (Throwable ignored) {
            System.out.print("{\"schemaVersion\":\"awx.desktop.mariadb-metadata.v1\","
                    + "\"decision\":\"internal_contract_failure\","
                    + "\"capturedAt\":null,\"readOnlySession\":false,\"currentCatalog\":null,"
                    + "\"server\":{},\"summary\":{},\"schemas\":[],\"tables\":[],"
                    + "\"columns\":[],\"indexes\":[],\"views\":[],\"queryIds\":[],"
                    + "\"metadataHash\":null,\"rawDbRowStored\":false,"
                    + "\"jdbcUrlStored\":false,\"evidenceNeeded\":[\"json-serialization-failed:Throwable\"]}");
        }
        System.exit(result.exitCode);
    }

    private static Result collect(Config config) {
        Connection connection;
        try {
            Class.forName(config.driverClass);
            Properties properties = new Properties();
            properties.setProperty("user", config.username);
            properties.setProperty("password", config.password);
            applyDriverTimeoutProperties(properties, config);
            connection = DriverManager.getConnection(config.jdbcUrl, properties);
        } catch (ClassNotFoundException | SQLException failure) {
            return Result.failure("connect_or_auth_failure", EXIT_CONNECT,
                    "database-connect-or-auth-failed", simpleClassName(failure));
        }

        Result result = null;
        String cleanupFailureClass = null;
        try {
            try {
                result = collectConnected(connection, config);
            } catch (MetadataLimitFailure failure) {
                result = Result.failure("query_or_limit_failure", EXIT_QUERY,
                        failure.reasonCode, failure.getClass().getSimpleName());
            } catch (SQLException failure) {
                result = Result.failure("query_or_limit_failure", EXIT_QUERY,
                        "metadata-query-failed", simpleClassName(failure));
            } catch (RuntimeException failure) {
                result = Result.failure("internal_contract_failure", EXIT_INTERNAL,
                        "internal-contract-failure", simpleClassName(failure));
            }
        } finally {
            try {
                connection.rollback();
            } catch (SQLException failure) {
                cleanupFailureClass = simpleClassName(failure);
            } finally {
                try {
                    connection.close();
                } catch (SQLException failure) {
                    if (cleanupFailureClass == null) {
                        cleanupFailureClass = simpleClassName(failure);
                    }
                }
            }
        }
        if (cleanupFailureClass != null && result.exitCode == EXIT_CONNECTED) {
            return Result.failure("query_or_limit_failure", EXIT_QUERY,
                    "database-cleanup-failed", cleanupFailureClass);
        }
        return result;
    }

    private static Result collectConnected(Connection connection, Config config)
            throws SQLException, MetadataLimitFailure {
        LinkedHashSet<String> observedQueryIds = new LinkedHashSet<>();
        Deadline deadline = new Deadline(config.timeoutSeconds);
        connection.setNetworkTimeout(command -> command.run(), config.timeoutMillis());
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            applyLimits(statement, config, 1);
            statement.execute(READ_ONLY_SQL);
        }
        observedQueryIds.add("session_read_only");

        String currentCatalog = connection.getCatalog();
        if (currentCatalog == null || currentCatalog.trim().isEmpty()) {
            throw new MetadataLimitFailure("current-catalog-unresolved");
        }
        if (currentCatalog.length() > MAX_METADATA_TEXT_LENGTH) {
            throw new MetadataLimitFailure("current-catalog-limit-exceeded");
        }
        if (isSystemCatalog(currentCatalog)) {
            throw new MetadataLimitFailure("system-catalog-forbidden");
        }

        Map<String, Object> server = readServerVariables(connection, config);
        observedQueryIds.add("server_variables");
        boolean serverReadOnly = readServerStatus(connection, config);
        observedQueryIds.add("server_status");
        boolean readOnlySession = connection.isReadOnly() && serverReadOnly;
        if (!readOnlySession) {
            throw new MetadataLimitFailure("read-only-session-not-observed");
        }
        deadline.check();

        String fingerprintStart = readStructuralFingerprint(
                connection, config, currentCatalog, deadline);
        observedQueryIds.add("metadata_fingerprint_start");

        RowBudget budget = new RowBudget(config.maxMetadataRows);
        DatabaseMetaData metadata = connection.getMetaData();
        List<Map<String, Object>> schemas = readCatalog(
                metadata, currentCatalog, budget, deadline);
        observedQueryIds.add("schemas");
        List<Map<String, Object>> tables = readTables(metadata, connection, config,
                currentCatalog, budget, deadline);
        observedQueryIds.add("tables");
        List<Map<String, Object>> views = readViews(
                connection, config, currentCatalog, budget, deadline);
        observedQueryIds.add("views");
        List<Map<String, Object>> columns = readColumns(
                metadata, currentCatalog, tables, views, budget, deadline);
        observedQueryIds.add("columns");
        List<Map<String, Object>> indexes = readIndexes(
                metadata, currentCatalog, tables, budget, deadline);
        observedQueryIds.add("indexes");

        String fingerprintEnd = readStructuralFingerprint(
                connection, config, currentCatalog, deadline);
        observedQueryIds.add("metadata_fingerprint_end");
        if (!fingerprintStart.equals(fingerprintEnd)) {
            throw new MetadataLimitFailure("metadata-changed-during-snapshot");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("catalogCount", schemas.size());
        summary.put("tableCount", tables.size());
        summary.put("columnCount", columns.size());
        summary.put("indexCount", indexes.size());
        summary.put("viewCount", views.size());
        summary.put("totalMetadataRows", budget.totalRows);
        summary.put("maxMetadataRows", config.maxMetadataRows);
        summary.put("timeoutSeconds", config.timeoutSeconds);
        summary.put("timeoutContract", timeoutContract(config.timeoutSeconds));
        summary.put("fingerprintStable", true);

        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("currentCatalog", currentCatalog);
        hashInput.put("server", server);
        hashInput.put("schemas", schemas);
        hashInput.put("tables", tables);
        hashInput.put("columns", columns);
        hashInput.put("indexes", indexes);
        hashInput.put("views", views);
        hashInput.put("fingerprint", fingerprintEnd);
        String metadataHash = sha256(Json.write(hashInput));

        Map<String, Object> out = emptyOutput("connected", true);
        out.put("readOnlySession", true);
        out.put("currentCatalog", currentCatalog);
        out.put("server", server);
        out.put("summary", summary);
        out.put("schemas", schemas);
        out.put("tables", tables);
        out.put("columns", columns);
        out.put("indexes", indexes);
        out.put("views", views);
        out.put("queryIds", canonicalQueryIds(observedQueryIds));
        out.put("metadataHash", metadataHash);
        return new Result("connected", EXIT_CONNECTED, out);
    }

    private static Map<String, Object> readServerVariables(Connection connection, Config config)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SERVER_VARIABLES_SQL)) {
            applyLimits(statement, config, 1);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("server-variables-empty");
                }
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("version", nullableText(rows.getString("server_version")));
                server.put("product", nullableText(rows.getString("server_product")));
                return server;
            }
        }
    }

    private static boolean readServerStatus(Connection connection, Config config) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SERVER_STATUS_SQL)) {
            applyLimits(statement, config, 1);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("server-status-empty");
                }
                return rows.getInt("read_only_session") == 1;
            }
        }
    }

    private static String readStructuralFingerprint(Connection connection, Config config,
            String catalog, Deadline deadline) throws SQLException, MetadataLimitFailure {
        StructuralFingerprint fingerprint = new StructuralFingerprint(config.maxMetadataRows);

        try (PreparedStatement statement = connection.prepareStatement(STRUCTURAL_TABLES_SQL)) {
            statement.setString(1, catalog);
            applyLimits(statement, config, config.maxMetadataRows);
            deadline.check();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    deadline.check();
                    addTableDescriptor(fingerprint,
                            rows.getString("table_name"),
                            rows.getString("table_type"),
                            rows.getString("engine"),
                            rows.getString("table_collation"));
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(STRUCTURAL_COLUMNS_SQL)) {
            statement.setString(1, catalog);
            applyLimits(statement, config, config.maxMetadataRows);
            deadline.check();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    deadline.check();
                    addColumnDescriptor(fingerprint,
                            rows.getString("table_name"),
                            rows.getString("column_name"),
                            rows.getInt("ordinal_position"),
                            rows.getString("column_type"),
                            rows.getString("is_nullable"),
                            rows.getString("character_set_name"),
                            rows.getString("collation_name"),
                            rows.getString("extra"));
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(STRUCTURAL_INDEXES_SQL)) {
            statement.setString(1, catalog);
            applyLimits(statement, config, config.maxMetadataRows);
            deadline.check();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    deadline.check();
                    addIndexDescriptor(fingerprint,
                            rows.getString("table_name"),
                            rows.getString("index_name"),
                            rows.getInt("non_unique"),
                            rows.getInt("seq_in_index"),
                            rows.getString("column_name"),
                            rows.getString("collation"),
                            rows.getString("index_type"),
                            nullableLong(rows, "sub_part"));
                }
            }
        }
        deadline.check();
        return fingerprint.finish();
    }

    private static List<Map<String, Object>> readCatalog(DatabaseMetaData metadata, String catalog,
            RowBudget budget, Deadline deadline) throws SQLException, MetadataLimitFailure {
        List<Map<String, Object>> schemas = new ArrayList<>();
        deadline.check();
        try (ResultSet rows = metadata.getCatalogs()) {
            while (rows.next()) {
                deadline.check();
                String candidate = rows.getString("TABLE_CAT");
                if (sameCatalog(catalog, candidate)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("catalog", nullableText(candidate));
                    budget.add(schemas, item);
                } else {
                    budget.observe();
                }
            }
        }
        if (schemas.isEmpty()) {
            throw new MetadataLimitFailure("current-catalog-not-listed");
        }
        return schemas;
    }

    private static List<Map<String, Object>> readTables(DatabaseMetaData metadata,
            Connection connection, Config config, String catalog, RowBudget budget,
            Deadline deadline)
            throws SQLException, MetadataLimitFailure {
        List<Map<String, Object>> tables = new ArrayList<>();
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        deadline.check();
        try (ResultSet rows = metadata.getTables(catalog, null, "%", new String[] { "TABLE" })) {
            while (rows.next()) {
                deadline.check();
                if (!sameCatalog(catalog, rows.getString("TABLE_CAT"))) {
                    budget.observe();
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                String name = nullableText(rows.getString("TABLE_NAME"));
                item.put("catalog", catalog);
                item.put("name", name);
                item.put("type", nullableText(rows.getString("TABLE_TYPE")));
                budget.add(tables, item);
                byName.put(name, item);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(TABLE_DETAILS_SQL)) {
            statement.setString(1, catalog);
            applyLimits(statement, config, config.maxMetadataRows);
            deadline.check();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    deadline.check();
                    Map<String, Object> item = byName.get(rows.getString("table_name"));
                    if (item != null) {
                        item.put("engine", nullableText(rows.getString("engine")));
                        item.put("rowEstimate", nullableLong(rows, "table_rows"));
                        item.put("dataBytes", nullableLong(rows, "data_length"));
                        item.put("indexBytes", nullableLong(rows, "index_length"));
                    }
                }
            }
        }
        return tables;
    }

    private static List<Map<String, Object>> readColumns(DatabaseMetaData metadata, String catalog,
            List<Map<String, Object>> tables, List<Map<String, Object>> views,
            RowBudget budget, Deadline deadline) throws SQLException, MetadataLimitFailure {
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> objects = new ArrayList<>();
        objects.addAll(tables);
        objects.addAll(views);
        for (Map<String, Object> object : objects) {
            String objectName = (String) object.get("name");
            deadline.check();
            try (ResultSet rows = metadata.getColumns(catalog, null, objectName, "%")) {
                while (rows.next()) {
                    deadline.check();
                    if (!sameCatalog(catalog, rows.getString("TABLE_CAT"))
                            || !objectName.equals(rows.getString("TABLE_NAME"))) {
                        budget.observe();
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("catalog", catalog);
                    item.put("table", nullableText(rows.getString("TABLE_NAME")));
                    item.put("name", nullableText(rows.getString("COLUMN_NAME")));
                    item.put("jdbcType", rows.getInt("DATA_TYPE"));
                    item.put("typeName", nullableText(rows.getString("TYPE_NAME")));
                    item.put("nullable", rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    item.put("ordinal", rows.getInt("ORDINAL_POSITION"));
                    budget.add(columns, item);
                }
            }
        }
        return columns;
    }

    private static List<Map<String, Object>> readIndexes(DatabaseMetaData metadata, String catalog,
            List<Map<String, Object>> tables, RowBudget budget, Deadline deadline)
            throws SQLException, MetadataLimitFailure {
        List<Map<String, Object>> indexes = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("name");
            deadline.check();
            try (ResultSet rows = metadata.getIndexInfo(catalog, null, tableName, false, true)) {
                while (rows.next()) {
                    deadline.check();
                    if (!sameCatalog(catalog, rows.getString("TABLE_CAT"))
                            || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                        budget.observe();
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("catalog", catalog);
                    item.put("table", tableName);
                    item.put("name", nullableText(rows.getString("INDEX_NAME")));
                    item.put("column", nullableText(rows.getString("COLUMN_NAME")));
                    item.put("nonUnique", rows.getBoolean("NON_UNIQUE"));
                    item.put("ordinal", rows.getInt("ORDINAL_POSITION"));
                    budget.add(indexes, item);
                }
            }
        }
        return indexes;
    }

    private static List<Map<String, Object>> readViews(Connection connection, Config config,
            String catalog, RowBudget budget, Deadline deadline)
            throws SQLException, MetadataLimitFailure {
        List<Map<String, Object>> views = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(VIEWS_SQL)) {
            statement.setString(1, catalog);
            applyLimits(statement, config, config.maxMetadataRows);
            deadline.check();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    deadline.check();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("catalog", catalog);
                    item.put("name", nullableText(rows.getString("table_name")));
                    item.put("checkOption", nullableText(rows.getString("check_option")));
                    item.put("updatable", "YES".equalsIgnoreCase(rows.getString("is_updatable")));
                    item.put("securityType", nullableText(rows.getString("security_type")));
                    budget.add(views, item);
                }
            }
        }
        return views;
    }

    private static void applyLimits(Statement statement, Config config, int maxRows)
            throws SQLException {
        statement.setQueryTimeout(config.timeoutSeconds);
        statement.setMaxRows(Math.max(1, Math.min(config.maxMetadataRows, maxRows)));
    }

    private static Long nullableLong(ResultSet rows, String label) throws SQLException {
        long value = rows.getLong(label);
        return rows.wasNull() ? null : value;
    }

    private static String nullableText(String value) {
        if (value == null || value.length() <= MAX_METADATA_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_METADATA_TEXT_LENGTH);
    }

    private static boolean sameCatalog(String expected, String candidate) {
        return candidate != null && expected.equalsIgnoreCase(candidate);
    }

    private static List<String> canonicalQueryIds(Set<String> observed) {
        List<String> result = new ArrayList<>();
        for (String queryId : ALLOWED_QUERY_IDS) {
            if (observed.contains(queryId)) {
                result.add(queryId);
            }
        }
        return result;
    }

    private static boolean isSystemCatalog(String catalog) {
        return catalog != null && SYSTEM_CATALOGS.contains(catalog.trim().toLowerCase(Locale.ROOT));
    }

    private static void applyDriverTimeoutProperties(Properties properties, Config config) {
        String timeoutMillis = Integer.toString(config.timeoutMillis());
        properties.setProperty("connectTimeout", timeoutMillis);
        properties.setProperty("socketTimeout", timeoutMillis);
    }

    private static Map<String, Object> timeoutContract(int timeoutSeconds) {
        Map<String, Object> contract = new LinkedHashMap<>();
        int timeoutMillis = timeoutSeconds * 1000;
        contract.put("networkTimeoutMillis", timeoutMillis);
        contract.put("connectTimeoutMillis", timeoutMillis);
        contract.put("socketTimeoutMillis", timeoutMillis);
        contract.put("childProcessDeadlineSeconds", timeoutSeconds + 5);
        return contract;
    }

    private static void addTableDescriptor(StructuralFingerprint fingerprint,
            String tableName, String tableType, String engine, String collation)
            throws MetadataLimitFailure {
        fingerprint.add("TABLE", tableName, tableType, engine, collation);
    }

    private static void addColumnDescriptor(StructuralFingerprint fingerprint,
            String tableName, String columnName, int ordinal, String columnType,
            String nullable, String characterSet, String collation, String extra)
            throws MetadataLimitFailure {
        fingerprint.add("COLUMN", tableName, columnName, Integer.toString(ordinal),
                columnType, nullable, characterSet, collation, extra);
    }

    private static void addIndexDescriptor(StructuralFingerprint fingerprint,
            String tableName, String indexName, int nonUnique, int sequence,
            String columnName, String collation, String indexType, Long prefixLength)
            throws MetadataLimitFailure {
        fingerprint.add("INDEX", tableName, indexName, Integer.toString(nonUnique),
                Integer.toString(sequence), columnName, collation, indexType,
                prefixLength == null ? null : prefixLength.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException("sha256-unavailable");
        }
    }

    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] hex = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            hex[index * 2] = alphabet[value >>> 4];
            hex[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(hex);
    }

    private static Map<String, Object> syntheticSelfTest() {
        Map<String, Object> out = emptyOutput("self_test_passed", true);
        out.put("readOnlySession", true);
        out.put("currentCatalog", "synthetic_catalog");
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("version", "synthetic");
        server.put("product", "synthetic");
        out.put("server", server);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("catalogCount", 1);
        summary.put("tableCount", 0);
        summary.put("columnCount", 0);
        summary.put("indexCount", 0);
        summary.put("viewCount", 0);
        summary.put("totalMetadataRows", 1);
        summary.put("fingerprintStable", true);
        summary.put("fingerprintContract", syntheticFingerprintContract());
        summary.put("systemCatalogContract", syntheticSystemCatalogContract());
        summary.put("timeoutContract", syntheticTimeoutContract());
        summary.put("rowCapContract", syntheticRowCapContract());
        out.put("summary", summary);
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("catalog", "synthetic_catalog");
        out.put("schemas", Collections.singletonList(catalog));
        out.put("queryIds", new ArrayList<>(ALLOWED_QUERY_IDS));
        out.put("metadataHash", sha256("synthetic-metadata"));

        String rendered = Json.write(out);
        boolean structurallyValid = rendered.startsWith("{") && rendered.endsWith("}")
                && rendered.contains("\"schemaVersion\":\"" + SCHEMA_VERSION + "\"")
                && rendered.contains("\"decision\":\"self_test_passed\"")
                && rendered.contains("\"readOnlySession\":true")
                && rendered.contains("\"rawDbRowStored\":false")
                && rendered.contains("\"jdbcUrlStored\":false")
                && "\"\\\"\\\\\\r\\n\\t\\u0001\"".equals(Json.write("\"\\\r\n\t\u0001"));
        boolean containsForbiddenValue = rendered.contains("jdbc:")
                || rendered.contains("AWX_SNAPSHOT_DB_PASSWORD")
                || rendered.contains("synthetic-secret");
        if (!structurallyValid || containsForbiddenValue) {
            throw new IllegalStateException("self-test-contract-failed");
        }
        return out;
    }

    private static Map<String, Object> syntheticFingerprintContract() {
        try {
            String baseline = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(32)", 1, "NO",
                    "external_id", 1, 1);
            String volatileChanged = syntheticStructuralFingerprint(
                    999999L, 987654321L, true, true, "varchar(32)", 1, "NO",
                    "external_id", 1, 1);
            String tableSetChanged = syntheticStructuralFingerprint(
                    10L, 1024L, false, true, "varchar(32)", 1, "NO",
                    "external_id", 1, 1);
            String columnTypeChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(64)", 1, "NO",
                    "external_id", 1, 1);
            String columnOrdinalChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(32)", 2, "NO",
                    "external_id", 1, 1);
            String columnNullabilityChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(32)", 1, "YES",
                    "external_id", 1, 1);
            String indexColumnChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(32)", 1, "NO",
                    "alternate_id", 1, 1);
            String indexOrderChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(32)", 1, "NO",
                    "external_id", 1, 2);
            String indexUniquenessChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, true, "varchar(32)", 1, "NO",
                    "external_id", 0, 1);
            String viewSetChanged = syntheticStructuralFingerprint(
                    10L, 1024L, true, false, "varchar(32)", 1, "NO",
                    "external_id", 1, 1);
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("stableAcrossVolatileEstimates", baseline.equals(volatileChanged));
            contract.put("changesForTableSet", !baseline.equals(tableSetChanged));
            contract.put("changesForColumnType", !baseline.equals(columnTypeChanged));
            contract.put("changesForColumnOrdinal", !baseline.equals(columnOrdinalChanged));
            contract.put("changesForColumnNullability", !baseline.equals(columnNullabilityChanged));
            contract.put("changesForIndexColumn", !baseline.equals(indexColumnChanged));
            contract.put("changesForIndexOrder", !baseline.equals(indexOrderChanged));
            contract.put("changesForIndexUniqueness", !baseline.equals(indexUniquenessChanged));
            contract.put("changesForViewSet", !baseline.equals(viewSetChanged));
            return contract;
        } catch (MetadataLimitFailure failure) {
            throw new IllegalStateException("synthetic-fingerprint-limit-failed");
        }
    }

    private static String syntheticStructuralFingerprint(long ignoredRowEstimate,
            long ignoredAllocatedBytes, boolean includeExtraTable, boolean includeView,
            String columnType, int columnOrdinal, String columnNullable,
            String indexColumn, int indexNonUnique, int indexOrder)
            throws MetadataLimitFailure {
        StructuralFingerprint fingerprint = new StructuralFingerprint(100);
        addTableDescriptor(fingerprint, "orders", "BASE TABLE", "InnoDB", "utf8mb4_bin");
        if (includeExtraTable) {
            addTableDescriptor(fingerprint, "audit_log", "BASE TABLE", "InnoDB", "utf8mb4_bin");
        }
        if (includeView) {
            addTableDescriptor(fingerprint, "orders_view", "VIEW", null, null);
        }
        addColumnDescriptor(fingerprint, "orders", "external_id", columnOrdinal, columnType,
                columnNullable, "utf8mb4", "utf8mb4_bin", "");
        addIndexDescriptor(fingerprint, "orders", "idx_external_id", indexNonUnique, indexOrder,
                indexColumn, "A", "BTREE", null);
        return fingerprint.finish();
    }

    private static Map<String, Object> syntheticSystemCatalogContract() {
        int rejected = 0;
        for (String catalog : SYSTEM_CATALOGS) {
            if (isSystemCatalog(catalog)) {
                rejected++;
            }
        }
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("rejectedCatalogCount", rejected);
        contract.put("mixedCaseRejected", isSystemCatalog("InFoRmAtIoN_ScHeMa"));
        return contract;
    }

    private static Map<String, Object> syntheticTimeoutContract() {
        Config config = new Config("synthetic", "synthetic", "synthetic", "synthetic",
                1000, 10);
        Properties properties = new Properties();
        applyDriverTimeoutProperties(properties, config);
        Map<String, Object> contract = timeoutContract(config.timeoutSeconds);
        contract.put("connectTimeoutMillis",
                Integer.parseInt(properties.getProperty("connectTimeout")));
        contract.put("socketTimeoutMillis",
                Integer.parseInt(properties.getProperty("socketTimeout")));
        return contract;
    }

    private static Map<String, Object> syntheticRowCapContract() {
        RowBudget budget = new RowBudget(2);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean limitRaised = false;
        String reasonCode = null;
        try {
            budget.add(rows, new LinkedHashMap<String, Object>());
            budget.add(rows, new LinkedHashMap<String, Object>());
        } catch (MetadataLimitFailure failure) {
            limitRaised = true;
            reasonCode = failure.reasonCode;
        }
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("maximum", 2);
        contract.put("acceptedRows", rows.size());
        contract.put("limitRaisedAtCap", limitRaised);
        contract.put("reasonCode", reasonCode);
        return contract;
    }

    private static Map<String, Object> emptyOutput(String decision, boolean captured) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", SCHEMA_VERSION);
        out.put("decision", decision);
        out.put("capturedAt", captured ? Instant.now().toString() : null);
        out.put("readOnlySession", false);
        out.put("currentCatalog", null);
        out.put("server", new LinkedHashMap<String, Object>());
        out.put("summary", new LinkedHashMap<String, Object>());
        out.put("schemas", new ArrayList<Object>());
        out.put("tables", new ArrayList<Object>());
        out.put("columns", new ArrayList<Object>());
        out.put("indexes", new ArrayList<Object>());
        out.put("views", new ArrayList<Object>());
        out.put("queryIds", new ArrayList<Object>());
        out.put("metadataHash", null);
        out.put("rawDbRowStored", false);
        out.put("jdbcUrlStored", false);
        out.put("evidenceNeeded", new ArrayList<Object>());
        return out;
    }

    private static String simpleClassName(Throwable failure) {
        String name = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        if (name == null || name.isEmpty()) {
            return "UnknownFailure";
        }
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_') {
                safe.append(ch);
            }
        }
        return safe.length() == 0 ? "UnknownFailure" : safe.toString();
    }

    private static final class Config {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final String driverClass;
        private final int maxMetadataRows;
        private final int timeoutSeconds;

        private Config(String jdbcUrl, String username, String password, String driverClass,
                int maxMetadataRows, int timeoutSeconds) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.driverClass = driverClass;
            this.maxMetadataRows = maxMetadataRows;
            this.timeoutSeconds = timeoutSeconds;
        }

        private static Config fromEnvironment() throws ConfigFailure {
            String jdbcUrl = required("AWX_SNAPSHOT_DB_URL");
            String username = required("AWX_SNAPSHOT_DB_USERNAME");
            String password = required("AWX_SNAPSHOT_DB_PASSWORD");
            if (!(jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:"))) {
                throw new ConfigFailure("database-url-scheme-unsupported");
            }
            String driverClass = System.getenv("AWX_SNAPSHOT_DB_DRIVER");
            if (driverClass == null || driverClass.trim().isEmpty()) {
                driverClass = "com.mysql.cj.jdbc.Driver";
            } else {
                driverClass = driverClass.trim();
            }
            int maxRows = boundedInteger("AWX_SNAPSHOT_DB_MAX_ROWS", 1000, 1, 10000);
            int timeout = boundedInteger("AWX_SNAPSHOT_DB_TIMEOUT_SECONDS", 10, 1, 30);
            return new Config(jdbcUrl, username, password, driverClass, maxRows, timeout);
        }

        private int timeoutMillis() {
            return timeoutSeconds * 1000;
        }

        private static String required(String name) throws ConfigFailure {
            String value = System.getenv(name);
            if (value == null || value.trim().isEmpty()) {
                throw new ConfigFailure("db-credentials-unresolved");
            }
            return value;
        }

        private static int boundedInteger(String name, int defaultValue, int minimum, int maximum)
                throws ConfigFailure {
            String raw = System.getenv(name);
            if (raw == null || raw.trim().isEmpty()) {
                return defaultValue;
            }
            try {
                long parsed = Long.parseLong(raw.trim());
                return (int) Math.max(minimum, Math.min(maximum, parsed));
            } catch (NumberFormatException failure) {
                throw new ConfigFailure("database-limit-invalid");
            }
        }
    }

    private static final class ConfigFailure extends Exception {
        private final String reasonCode;

        private ConfigFailure(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
    }

    private static final class MetadataLimitFailure extends Exception {
        private final String reasonCode;

        private MetadataLimitFailure(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
    }

    private static final class Deadline {
        private final long expiresAtNanos;

        private Deadline(int timeoutSeconds) {
            this.expiresAtNanos = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        }

        private void check() throws MetadataLimitFailure {
            if (System.nanoTime() - expiresAtNanos >= 0) {
                throw new MetadataLimitFailure("metadata-deadline-exceeded");
            }
        }
    }

    private static final class StructuralFingerprint {
        private final MessageDigest digest;
        private final int maximumRows;
        private int rows;

        private StructuralFingerprint(int maximumRows) {
            this.maximumRows = maximumRows;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception failure) {
                throw new IllegalStateException("sha256-unavailable");
            }
        }

        private void add(String... fields) throws MetadataLimitFailure {
            for (String field : fields) {
                if (field == null) {
                    updateLength(-1);
                } else {
                    byte[] value = nullableText(field).getBytes(StandardCharsets.UTF_8);
                    updateLength(value.length);
                    digest.update(value);
                }
            }
            rows++;
            if (rows >= maximumRows) {
                throw new MetadataLimitFailure("metadata-row-limit-reached");
            }
        }

        private void updateLength(int length) {
            digest.update((byte) ((length >>> 24) & 0xff));
            digest.update((byte) ((length >>> 16) & 0xff));
            digest.update((byte) ((length >>> 8) & 0xff));
            digest.update((byte) (length & 0xff));
        }

        private String finish() {
            return toHex(digest.digest());
        }
    }

    private static final class RowBudget {
        private final int maximum;
        private int totalRows;

        private RowBudget(int maximum) {
            this.maximum = maximum;
        }

        private void add(List<Map<String, Object>> collection, Map<String, Object> item)
                throws MetadataLimitFailure {
            if (collection.size() >= maximum || totalRows >= maximum) {
                throw new MetadataLimitFailure("metadata-row-limit-reached");
            }
            collection.add(item);
            observe();
        }

        private void observe() throws MetadataLimitFailure {
            if (totalRows >= maximum) {
                throw new MetadataLimitFailure("metadata-row-limit-reached");
            }
            totalRows++;
            if (totalRows >= maximum) {
                throw new MetadataLimitFailure("metadata-row-limit-reached");
            }
        }
    }

    private static final class Result {
        private final String decision;
        private final int exitCode;
        private final Map<String, Object> output;

        private Result(String decision, int exitCode, Map<String, Object> output) {
            this.decision = decision;
            this.exitCode = exitCode;
            this.output = output;
        }

        private static Result failure(String decision, int exitCode, String reasonCode,
                String failureClass) {
            Map<String, Object> output = emptyOutput(decision, true);
            List<String> evidenceNeeded = new ArrayList<>();
            evidenceNeeded.add(reasonCode + ":" + failureClass);
            output.put("evidenceNeeded", evidenceNeeded);
            return new Result(decision, exitCode, output);
        }

        private Map<String, Object> toMap() {
            return output;
        }
    }

    private static final class Json {
        private Json() {
        }

        private static String write(Object value) {
            StringBuilder out = new StringBuilder();
            append(out, value);
            return out.toString();
        }

        private static void append(StringBuilder out, Object value) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String) {
                out.append('"').append(escape((String) value)).append('"');
            } else if (value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof Number) {
                if ((value instanceof Double && !Double.isFinite((Double) value))
                        || (value instanceof Float && !Float.isFinite((Float) value))) {
                    throw new IllegalArgumentException("json-non-finite-number");
                }
                out.append(value);
            } else if (value instanceof Map<?, ?>) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) {
                        throw new IllegalArgumentException("json-map-key-not-string");
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, entry.getKey());
                    out.append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            } else if (value instanceof Iterable<?>) {
                out.append('[');
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, item);
                }
                out.append(']');
            } else {
                throw new IllegalArgumentException("json-type-unsupported");
            }
        }

        private static String escape(String value) {
            StringBuilder escaped = new StringBuilder(value.length() + 8);
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                switch (ch) {
                    case '"':
                        escaped.append("\\\"");
                        break;
                    case '\\':
                        escaped.append("\\\\");
                        break;
                    case '\r':
                        escaped.append("\\r");
                        break;
                    case '\n':
                        escaped.append("\\n");
                        break;
                    case '\t':
                        escaped.append("\\t");
                        break;
                    default:
                        if (ch < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) ch));
                        } else {
                            escaped.append(ch);
                        }
                }
            }
            return escaped.toString();
        }
    }
}
