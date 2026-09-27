package com.example.lms;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Phase2ApplicationRoleTest extends Phase2ApplicationTestSupport {
    private static final String DATABASE = "jdbc:h2:mem:phase2-role-" + UUID.randomUUID()
            + ";MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("domain.allowlist.admin-token", () -> "");
        registry.add("domain.allowlist.admin-token.required", () -> "false");
    }

    @Test void actualDaoFormLoginSeparatesAnonymousInsufficientAndAdminRoles() throws Exception {
        var anonymous = client();
        var user = login(account("ROLE_USER"));
        var admin = login(account("ROLE_ADMIN"));
        for (String path : DIAGNOSTICS) {
            var rejected = get(anonymous, path, null);
            assertThat(rejected.statusCode()).isEqualTo(302);
            assertThat(rejected.headers().firstValue("Location").orElseThrow()).endsWith("/login");
            assertThat(get(user, path, null).statusCode()).isEqualTo(403);
            assertJson(get(admin, path, null));
        }
        assertThat(get(user, "/admin/debug-events", null).statusCode()).isEqualTo(403);
        assertThat(get(admin, "/admin/debug-events", null).statusCode()).isEqualTo(200);
        // A session role does not replace the independent tool capability contract.
        assertThat(get(admin, "/internal/agent/tools", null).statusCode()).isEqualTo(403);
    }
}
