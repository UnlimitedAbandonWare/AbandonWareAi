package com.example.lms.api;

import com.example.lms.service.rag.auth.DomainProfileLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainProfileControllerTest {

    @Test
    void reloadFailsClosedWhenNoAdminTokenAndNoAdminRole() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getAdminToken()).thenReturn("");
        DomainProfileController controller = new DomainProfileController(loader);

        ResponseEntity<Map<String, Object>> response = controller.reload(null, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(loader, never()).reload();
    }

    @Test
    void reloadAllowsMatchingAdminToken() {
        DomainProfileLoader loader = loaderWithProfiles();
        when(loader.getAdminToken()).thenReturn("admin-token");
        DomainProfileController controller = new DomainProfileController(loader);

        ResponseEntity<Map<String, Object>> response = controller.reload("admin-token", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(loader).reload();
    }

    @Test
    void reloadAllowsAuthenticatedAdminRoleWithoutToken() {
        DomainProfileLoader loader = loaderWithProfiles();
        when(loader.getAdminToken()).thenReturn("");
        DomainProfileController controller = new DomainProfileController(loader);
        TestingAuthenticationToken auth = new TestingAuthenticationToken(
                "admin",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        auth.setAuthenticated(true);

        ResponseEntity<Map<String, Object>> response = controller.reload(null, auth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(loader).reload();
    }

    @Test
    void reloadRejectsWrongAdminToken() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getAdminToken()).thenReturn("admin-token");
        DomainProfileController controller = new DomainProfileController(loader);

        ResponseEntity<Map<String, Object>> response = controller.reload("wrong", null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(loader, never()).reload();
    }

    private static DomainProfileLoader loaderWithProfiles() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getDefaultProfile()).thenReturn("official");
        when(loader.listProfiles()).thenReturn(List.of(Map.of("name", "official", "count", 1)));
        return loader;
    }
}
