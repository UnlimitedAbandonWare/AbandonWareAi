package com.example.lms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.lms.infra.selection.SelectionEntropyCoherence;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.security.AdminTokenGuardInterceptor;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class SelectionReplayRequestResolverTest {

    @Mock
    private AdminTokenGuardInterceptor guard;

    @Test
    void headerAbsenceUsesStandardWithoutConsultingAdminGuard() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        SelectionReplayRequestResolver.Resolved resolved = resolver().resolve(request);
        SelectionReplayRequestResolver.Resolved nullRequest = resolver().resolve(null);

        assertThat(resolved.entropy().mode()).isEqualTo(SelectionEntropyMode.STANDARD);
        assertThat(resolved.ledger().snapshot(false).coherence())
                .isEqualTo(SelectionEntropyCoherence.NOT_REQUESTED);
        assertThat(nullRequest.entropy().mode()).isEqualTo(SelectionEntropyMode.STANDARD);
        verifyNoInteractions(guard);
    }

    @Test
    void unauthorizedMalformedValueRevealsOnlyForbidden() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SelectionReplayRequestResolver.HEADER, "visibly malformed secret");
        when(guard.isPresentedHeaderTokenAuthorized(request)).thenReturn(false);

        SelectionReplayRequestException failure = catchThrowableOfType(
                () -> resolver().resolve(request), SelectionReplayRequestException.class);

        assertThat(failure.code()).isEqualTo("selection_entropy_replay_forbidden");
        assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(failure).hasMessage("selection_entropy_replay_forbidden");
        assertThat(failure.toString()).doesNotContain("visibly", "malformed", "secret");
        verify(guard, times(1)).isPresentedHeaderTokenAuthorized(request);
    }

    @Test
    void authorizedValidHeaderCreatesReplayWithoutRetainingHeaderText() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        request.addHeader(SelectionReplayRequestResolver.HEADER, "v1:" + encoded);
        when(guard.isPresentedHeaderTokenAuthorized(request)).thenReturn(true);

        SelectionReplayRequestResolver.Resolved resolved = resolver().resolve(request);

        assertThat(resolved.entropy().mode()).isEqualTo(SelectionEntropyMode.REPLAY);
        assertThat(resolved.ledger().snapshot(false).coherence())
                .isEqualTo(SelectionEntropyCoherence.ACCEPTED);
        assertThat(resolved.toString()).doesNotContain(encoded);
        verify(guard, times(1)).isPresentedHeaderTokenAuthorized(request);
    }

    @Test
    void duplicateValuesAreRejectedOnlyAfterOneAuthorizationDecision() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SelectionReplayRequestResolver.HEADER, "v1:AAAA");
        request.addHeader(SelectionReplayRequestResolver.HEADER, "v1:BBBB");
        when(guard.isPresentedHeaderTokenAuthorized(request)).thenReturn(true);

        assertFailure(request, "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST);
        verify(guard, times(1)).isPresentedHeaderTokenAuthorized(request);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidHeaders")
    void authorizedInvalidHeadersUseOnlyFixedCodes(
            String label,
            String value,
            String expectedCode,
            HttpStatus expectedStatus) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SelectionReplayRequestResolver.HEADER, value);
        when(guard.isPresentedHeaderTokenAuthorized(request)).thenReturn(true);

        SelectionReplayRequestException failure =
                assertFailure(request, expectedCode, expectedStatus);

        if (!value.isEmpty()) {
            assertThat(failure.toString()).doesNotContain(value);
        }
        verify(guard, times(1)).isPresentedHeaderTokenAuthorized(request);
    }

    @Test
    void fixedExceptionFactoriesExposeOnlyStatusAndCode() {
        assertFixed(
                SelectionReplayRequestException.forbidden(),
                HttpStatus.FORBIDDEN,
                "selection_entropy_replay_forbidden");
        assertFixed(
                SelectionReplayRequestException.invalid(),
                HttpStatus.BAD_REQUEST,
                "selection_entropy_replay_invalid");
        assertFixed(
                SelectionReplayRequestException.unsupported(),
                HttpStatus.BAD_REQUEST,
                "selection_entropy_algorithm_unsupported");
        assertFixed(
                SelectionReplayRequestException.initializationFailed(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "selection_entropy_replay_init_failed");
    }

    private SelectionReplayRequestException assertFailure(
            MockHttpServletRequest request,
            String expectedCode,
            HttpStatus expectedStatus) {
        SelectionReplayRequestException failure = catchThrowableOfType(
                () -> resolver().resolve(request), SelectionReplayRequestException.class);
        assertThat(failure.code()).isEqualTo(expectedCode);
        assertThat(failure.status()).isEqualTo(expectedStatus);
        assertThat(failure).hasMessage(expectedCode);
        assertThat(failure.getCause()).isNull();
        return failure;
    }

    private static void assertFixed(
            SelectionReplayRequestException failure,
            HttpStatus status,
            String code) {
        assertThat(failure.status()).isEqualTo(status);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure).hasMessage(code);
        assertThat(failure.getCause()).isNull();
    }

    private SelectionReplayRequestResolver resolver() {
        return new SelectionReplayRequestResolver(guard);
    }

    private static Stream<Arguments> invalidHeaders() {
        String seed15 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[15]);
        String seed65 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
        String seed32 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        return Stream.of(
                Arguments.of("blank", "", "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("whitespace", "v1:AA AA", "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("padding", "v1:AAAA=", "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("standard-base64", "v1:AA+A", "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("short-seed", "v1:" + seed15, "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("long-seed", "v1:" + seed65, "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("over-96", "v1:" + "A".repeat(94), "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST),
                Arguments.of("unknown-version", "v2:" + seed32,
                        "selection_entropy_algorithm_unsupported", HttpStatus.BAD_REQUEST),
                Arguments.of("two-separators", "v1:AA:AA", "selection_entropy_replay_invalid", HttpStatus.BAD_REQUEST));
    }
}
