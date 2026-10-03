package com.example.lms.api;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 로컬 에이전트용 administrators 관리면. 라이브 서버가 H2 파일을 잡고 있어
 * JDBC가 잠길 때( exit 3 )에도 애플리케이션 DataSource 경유로 관리자 행을
 * 읽거나 MERGE upsert할 수 있다. {@link MetaDisplayDbQueryController}와 같은
 * /api/internal/** 토큰 가드 아래에 있으며, 비밀번호는 요청 본문으로만 받고
 * 저장 해시는 접두어(7자)만 응답한다. 브라이트크립트가 아닌 평문만 받는다.
 */
@RestController
@RequestMapping("/api/internal/db/meta/admin")
@Profile("meta-display")
@ConditionalOnProperty(name = "meta.db.admin.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class MetaDisplayDbAdminController {

    private static final int HASH_PREFIX_LEN = 7;
    private static final int MAX_FIELD_LEN = 200;
    private static final int MAX_PASSWORD_LEN = 512;

    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestParam("username") String username) {
        String uname = trimToNull(username);
        if (uname == null || uname.length() > MAX_FIELD_LEN) {
            return fail(HttpStatus.BAD_REQUEST, "invalid_username");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT username, role, name, created_at, LEFT(password, " + HASH_PREFIX_LEN + ") "
                             + "FROM administrators WHERE username = ?")) {
            ps.setString(1, uname);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Object> out = ok();
                if (rs.next()) {
                    out.put("found", true);
                    out.put("username", rs.getString(1));
                    out.put("role", rs.getString(2));
                    out.put("name", rs.getString(3));
                    Object created = rs.getObject(4);
                    out.put("createdAt", created == null ? null : String.valueOf(created));
                    out.put("hashPrefix", rs.getString(5));
                } else {
                    out.put("found", false);
                    out.put("username", uname);
                }
                return ResponseEntity.ok(out);
            }
        } catch (SQLException e) {
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "admin_read_failed", e);
        }
    }

    /** password는 본문 필드로만 받는다(쿼리스트링/로그 경로에 절대 올리지 않음). */
    public record UpsertRequest(String username, String password, String role, String name) { }

    @PostMapping("/upsert")
    public ResponseEntity<Map<String, Object>> upsert(
            @RequestBody(required = false) UpsertRequest body) {
        if (body == null) {
            return fail(HttpStatus.BAD_REQUEST, "body_required");
        }
        String username = trimToNull(body.username());
        String plaintext = body.password();
        String role = body.role() != null && !body.role().isBlank() ? body.role().trim() : "ROLE_ADMIN";
        String name = body.name() != null && !body.name().isBlank() ? body.name().trim() : username;
        if (username == null || username.length() > MAX_FIELD_LEN) {
            return fail(HttpStatus.BAD_REQUEST, "invalid_username");
        }
        if (role.length() > MAX_FIELD_LEN || !role.startsWith("ROLE_")) {
            return fail(HttpStatus.BAD_REQUEST, "invalid_role");
        }
        if (plaintext == null || plaintext.isEmpty() || plaintext.length() > MAX_PASSWORD_LEN) {
            return fail(HttpStatus.BAD_REQUEST, "invalid_password");
        }
        if (plaintext.startsWith("$2")) {
            // bcrypt-모양 입력을 그대로 MERGE하면 이중 해시가 되어 로그인이 깨진다.
            return fail(HttpStatus.BAD_REQUEST, "password_must_be_plaintext");
        }
        String stored = passwordEncoder.encode(plaintext);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "MERGE INTO administrators (username, password, role, name) "
                             + "KEY(username) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, stored);
            ps.setString(3, role);
            ps.setString(4, name);
            int updated = ps.executeUpdate();
            Map<String, Object> out = ok();
            out.put("applied", updated > 0);
            out.put("username", username);
            out.put("role", role);
            out.put("hashPrefix", stored.substring(0, Math.min(HASH_PREFIX_LEN, stored.length())));
            return ResponseEntity.ok(out);
        } catch (SQLException e) {
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "admin_upsert_failed", e);
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private static String trimToNull(Object value) {
        if (!(value instanceof String s)) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", code);
        return ResponseEntity.status(status).body(out);
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
}
