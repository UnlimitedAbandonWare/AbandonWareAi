package com.example.lms.api;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 로컬 에이전트(Grok/Devin/Codex/Cline)용 Meta Display 데이터소스 읽기 전용 조회면.
 * 기존 /api/internal/** 토큰 가드(필터+인터셉터+hasRole(ADMIN))를 그대로 타며,
 * H2 라이브 파일을 직접 열지 않고 애플리케이션 DataSource 경유 SELECT만 허용한다.
 * 쓰기/DDL/파일함수는 {@link SqlGate}가 차단하고 결과 행수는 상한으로 자른다.
 */
@RestController
@RequestMapping("/api/internal/db/meta")
@Profile("meta-display")
@ConditionalOnProperty(name = "meta.db.query.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class MetaDisplayDbQueryController {

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int HARD_MAX_ROWS = 5_000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int CELL_CHAR_CAP = 64_000;

    private final DataSource dataSource;

    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> tables() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = readOnlyConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                             "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA <> 'INFORMATION_SCHEMA' " +
                             "ORDER BY TABLE_SCHEMA, TABLE_NAME");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String name = rs.getString(2);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("schema", schema);
                t.put("name", name);
                t.put("rowCount", rowCount(c, schema, name));
                out.add(t);
            }
        } catch (SQLException e) {
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "tables_failed", e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("tables", out);
        body.put("tableCount", out.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/columns")
    public ResponseEntity<Map<String, Object>> columns(@RequestParam("table") String table) {
        String schema = "PUBLIC";
        String name = table;
        int dot = table.indexOf('.');
        if (dot > 0) {
            schema = table.substring(0, dot);
            name = table.substring(dot + 1);
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        try (Connection c = readOnlyConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                             "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString(1));
                    col.put("type", rs.getString(2));
                    cols.add(col);
                }
            }
        } catch (SQLException e) {
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "columns_failed", e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("table", name);
        body.put("schema", schema);
        body.put("columns", cols);
        return ResponseEntity.ok(body);
    }

    @RequestMapping(value = "/query", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam(name = "sql", required = false) String sqlParam,
            @RequestParam(name = "maxRows", required = false) Integer maxRowsParam,
            @RequestBody(required = false) Map<String, Object> body) {
        String sql = sqlParam;
        Integer maxRows = maxRowsParam;
        if (body != null) {
            if (sql == null && body.get("sql") instanceof String s) sql = s;
            if (maxRows == null && body.get("maxRows") instanceof Number n) maxRows = n.intValue();
        }
        String reason = SqlGate.check(sql);
        if (reason != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "sql_rejected");
            out.put("reason", reason);
            return ResponseEntity.badRequest().body(out);
        }
        int cap = clampMaxRows(maxRows);
        try (Connection c = readOnlyConnection();
             Statement st = c.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            st.setMaxRows(cap + 1);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> columns = new ArrayList<>(n);
                List<String> types = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(md.getColumnLabel(i));
                    types.add(md.getColumnTypeName(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                int total = 0;
                boolean truncated = false;
                while (rs.next()) {
                    total++;
                    if (rows.size() < cap) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) {
                            row.put(columns.get(i - 1), cellValue(rs.getObject(i)));
                        }
                        rows.add(row);
                    } else {
                        truncated = true;
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("columns", columns);
                out.put("types", types);
                out.put("rows", rows);
                out.put("rowCount", rows.size());
                out.put("totalMatched", total);
                out.put("truncated", truncated);
                out.put("maxRows", cap);
                return ResponseEntity.ok(out);
            }
        } catch (SQLException e) {
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "query_failed", e);
        }
    }

    private Connection readOnlyConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setReadOnly(true);
        return c;
    }

    private static Long rowCount(Connection c, String schema, String name) {
        String sql = "SELECT COUNT(*) FROM \"" + schema.replace("\"", "") + "\".\"" + name.replace("\"", "") + "\"";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static int clampMaxRows(Integer maxRows) {
        if (maxRows == null || maxRows <= 0) return DEFAULT_MAX_ROWS;
        return Math.min(maxRows, HARD_MAX_ROWS);
    }

    private static Object cellValue(Object v) {
        if (v == null) return null;
        if (v instanceof byte[] b) return "<bytes:" + b.length + ">";
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof Date || v instanceof Time) return v.toString();
        if (v instanceof String s) return s.length() > CELL_CHAR_CAP ? s.substring(0, CELL_CHAR_CAP) : s;
        return v;
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String code, SQLException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", code);
        String msg = e.getMessage();
        out.put("reason", msg == null ? e.getClass().getSimpleName()
                : msg.length() > 300 ? msg.substring(0, 300) : msg);
        return ResponseEntity.status(status).body(out);
    }

    /**
     * 읽기 전용 SQL 게이트. 문자열 리터럴과 주석을 지운 뒤 쓰기/DDL/파일함수 토큰과
     * 다중 문장을 거부한다. 허용: SELECT / WITH / TABLE / VALUES / EXPLAIN으로 시작하는 단일 문장.
     */
    static final class SqlGate {
        private static final int SQL_CHAR_CAP = 8_000;
        private static final Pattern FIRST_WORD = Pattern.compile("^([A-Za-z]+)");
        private static final Pattern DENY_WORD = Pattern.compile(
                "\\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|set|"
                        + "script|shutdown|checkpoint|backup|call|prepare|execute|commit|rollback|"
                        + "savepoint|lock|comment|analyze|runscript|rename|vacuum|attach|detach|"
                        + "replace|upsert|csvwrite|csvread|file_read|file_write|link_schema)\\b",
                Pattern.CASE_INSENSITIVE);
        private static final java.util.Set<String> ALLOWED_START =
                java.util.Set.of("select", "with", "table", "values", "explain");

        private SqlGate() {
        }

        /** 허용이면 null, 거부면 사유 문자열. */
        static String check(String sql) {
            if (sql == null || sql.isBlank()) return "empty-sql";
            if (sql.length() > SQL_CHAR_CAP) return "sql-too-long(" + sql.length() + ">" + SQL_CHAR_CAP + ")";
            String scrubbed = scrubLiteralsAndComments(sql).strip();
            if (scrubbed.endsWith(";")) {
                scrubbed = scrubbed.substring(0, scrubbed.length() - 1).strip();
            }
            var m = FIRST_WORD.matcher(scrubbed);
            if (!m.find() || !ALLOWED_START.contains(m.group(1).toLowerCase())) {
                return "first-token-not-allowed";
            }
            var d = DENY_WORD.matcher(scrubbed);
            if (d.find()) return "write-or-file-word:" + d.group(1).toLowerCase();
            if (scrubbed.indexOf(';') >= 0) return "multi-statement";
            return null;
        }

        /** 작은따옴표 리터럴(쌍따옴표 이스케이프 포함)과 --/x--x 주석을 공백으로 치환. */
        private static String scrubLiteralsAndComments(String sql) {
            StringBuilder out = new StringBuilder(sql.length());
            int i = 0;
            int n = sql.length();
            while (i < n) {
                char ch = sql.charAt(i);
                if (ch == '\'') {
                    out.append(' ');
                    i++;
                    while (i < n) {
                        if (sql.charAt(i) == '\'') {
                            if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                                i += 2;
                                continue;
                            }
                            i++;
                            break;
                        }
                        i++;
                    }
                } else if (ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                    while (i < n && sql.charAt(i) != '\n') i++;
                } else if (ch == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                    i = Math.min(i + 2, n);
                } else {
                    out.append(ch);
                    i++;
                }
            }
            return out.toString();
        }
    }
}
