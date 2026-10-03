package com.example.lms.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic in-memory H2 store; never touches the real file DB. */
class MetaDisplayDbAdminControllerTest {

    /** h2 stays runtimeOnly; DriverManager resolves it on the test classpath. */
    private static final class MemDataSource implements DataSource {
        private final String url;

        MemDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return DriverManager.getConnection(url, u, p);
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private DataSource dataSource;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private MetaDisplayDbAdminController controller;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new MemDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute(
                    "CREATE TABLE administrators ("
                            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                            + "username VARCHAR(255) UNIQUE, "
                            + "password VARCHAR(255), role VARCHAR(64), "
                            + "name VARCHAR(255), created_at TIMESTAMP)");
        }
        controller = new MetaDisplayDbAdminController(dataSource, encoder);
    }

    private MetaDisplayDbAdminController.UpsertRequest body(
            String username, String password, String role, String name) {
        return new MetaDisplayDbAdminController.UpsertRequest(username, password, role, name);
    }

    private String storedHash(String username) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT password FROM administrators WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void upsertCreatesRowWithBcryptAndMaskedPrefixOnly() throws Exception {
        ResponseEntity<Map<String, Object>> res = controller.upsert(
                body("agent-admin", "synthetic-pass-1", null, null));
        assertEquals(200, res.getStatusCode().value());
        Map<String, Object> out = res.getBody();
        assertNotNull(out);
        assertEquals(true, out.get("applied"));
        assertEquals("agent-admin", out.get("username"));
        assertEquals("ROLE_ADMIN", out.get("role"));
        assertEquals("$2a$10$", out.get("hashPrefix"));
        assertFalse(out.containsKey("password"));
        String stored = storedHash("agent-admin");
        assertNotNull(stored);
        assertTrue(encoder.matches("synthetic-pass-1", stored));
        assertFalse(stored.contains("synthetic-pass-1"));
    }

    @Test
    void upsertRepairsExistingRow() throws Exception {
        controller.upsert(body("admin", "synthetic-pass-1", null, null));
        ResponseEntity<Map<String, Object>> res = controller.upsert(
                body("admin", "synthetic-pass-2", "ROLE_ADMIN", "Boss"));
        assertEquals(200, res.getStatusCode().value());
        String stored = storedHash("admin");
        assertTrue(encoder.matches("synthetic-pass-2", stored));
        ResponseEntity<Map<String, Object>> get = controller.get("admin");
        assertEquals(200, get.getStatusCode().value());
        assertEquals(true, get.getBody().get("found"));
        assertEquals("Boss", get.getBody().get("name"));
    }

    @Test
    void getReturnsFoundFalseForMissingUserNot404() {
        ResponseEntity<Map<String, Object>> res = controller.get("nobody");
        // 404 means "endpoint predates this feature" for the client - a missing
        // row must stay HTTP 200 with found=false.
        assertEquals(200, res.getStatusCode().value());
        assertEquals(false, res.getBody().get("found"));
    }

    @Test
    void validationRejectsBadInput() {
        assertEquals(400, controller.upsert(body(null, "x", null, null)).getStatusCode().value());
        assertEquals(400, controller.upsert(body("u", null, null, null)).getStatusCode().value());
        assertEquals(400, controller.upsert(body("u", "", null, null)).getStatusCode().value());
        assertEquals(400, controller.upsert(body("u", "x", "ADMIN", null)).getStatusCode().value());
        assertEquals(400, controller.upsert(null).getStatusCode().value());
    }

    @Test
    void prehashedPasswordIsRejectedToAvoidSilentDoubleHash() {
        ResponseEntity<Map<String, Object>> res = controller.upsert(
                body("u2", "$2a$10$abcdefghijklmnopqrstuv", null, null));
        assertEquals(400, res.getStatusCode().value());
        assertEquals("password_must_be_plaintext", res.getBody().get("error"));
        assertNull(safeHash("u2"));
    }

    private String safeHash(String username) {
        try {
            return storedHash(username);
        } catch (Exception e) {
            return null;
        }
    }
}
