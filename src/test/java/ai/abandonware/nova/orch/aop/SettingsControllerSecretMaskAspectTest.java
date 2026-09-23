package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsControllerSecretMaskAspectTest {

    @Test
    void secretMaskTraceCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/SettingsControllerSecretMaskAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"settingsSecretMask.getTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"settingsSecretMask.saveTrace\", ignore);"));
    }

    @Test
    void getAllSettingsMasksSupabaseKeyShapedValues() throws Throwable {
        SettingsControllerSecretMaskAspect aspect = new SettingsControllerSecretMaskAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String secretKey = "sb_secret_" + "settings01";
        String publishableKey = "sb_publishable_" + "settings01";
        when(pjp.proceed()).thenReturn(ResponseEntity.ok(Map.of(
                "safeSetting", "visible",
                "displayValue1", secretKey,
                "displayValue2", publishableKey)));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>) aspect.aroundGetAllSettings(pjp);
        Map<String, String> body = response.getBody();

        assertEquals("visible", body.get("safeSetting"));
        assertFalse(body.get("displayValue1").contains(secretKey), body.get("displayValue1"));
        assertFalse(body.get("displayValue2").contains(publishableKey), body.get("displayValue2"));
        assertTrue(body.get("displayValue1").startsWith("***"), body.get("displayValue1"));
        assertTrue(body.get("displayValue2").startsWith("***"), body.get("displayValue2"));
    }

    @Test
    void getAllSettingsMasksJdbcCredentialsUnderAnInnocuousKey() throws Throwable {
        assertMasked("displayJdbc", "jdbc:postgresql://demo_user:demo_password@db.invalid/demo");
    }

    @Test
    void getAllSettingsMasksAwsAccessKeySettings() throws Throwable {
        assertMasked("aws_access_key_id", "AKIA"" + ""0000000000000000");
    }

    @Test
    void getAllSettingsMasksPemPrivateKeyMaterial() throws Throwable {
        assertMasked("displayPem",
                "-----BEGIN "" + ""PRIVATE KEY-----\nsynthetic-test-material\n-----END "" + ""PRIVATE KEY-----");
    }

    @Test
    void getAllSettingsDoesNotTreatQueryTextAsUriUserInfo() throws Throwable {
        String safeUrl = "https://example.invalid?contact=team:ops@example.invalid";
        SettingsControllerSecretMaskAspect aspect = new SettingsControllerSecretMaskAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ResponseEntity.ok(Map.of("safeUrl", safeUrl)));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response =
                (ResponseEntity<Map<String, String>>) aspect.aroundGetAllSettings(pjp);

        assertEquals(safeUrl, response.getBody().get("safeUrl"));
    }

    private static void assertMasked(String key, String secret) throws Throwable {
        SettingsControllerSecretMaskAspect aspect = new SettingsControllerSecretMaskAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ResponseEntity.ok(Map.of(
                "safeSetting", "visible",
                key, secret)));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response =
                (ResponseEntity<Map<String, String>>) aspect.aroundGetAllSettings(pjp);
        Map<String, String> body = response.getBody();

        assertEquals("visible", body.get("safeSetting"));
        assertFalse(body.get(key).contains(secret));
        assertTrue(body.get(key).startsWith("***"));
    }
}
