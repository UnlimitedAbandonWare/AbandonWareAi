package com.example.lms.api;

import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyException;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.security.AdminTokenGuardInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class SelectionReplayRequestResolver {

    public static final String HEADER = "X-AWX-Selection-Replay";

    private static final int MAX_HEADER_CHARS = 96;
    private static final Pattern PAYLOAD = Pattern.compile("[A-Za-z0-9_-]+");

    private final AdminTokenGuardInterceptor adminTokenGuard;

    public SelectionReplayRequestResolver(AdminTokenGuardInterceptor adminTokenGuard) {
        this.adminTokenGuard = Objects.requireNonNull(adminTokenGuard, "adminTokenGuard");
    }

    public Resolved resolve(HttpServletRequest request) {
        Enumeration<String> headers = request == null ? null : request.getHeaders(HEADER);
        List<String> values = headers == null ? List.of() : Collections.list(headers);
        if (values.isEmpty()) {
            return Resolved.standard();
        }
        if (!adminTokenGuard.isPresentedHeaderTokenAuthorized(request)) {
            throw SelectionReplayRequestException.forbidden();
        }
        if (values.size() != 1) {
            throw SelectionReplayRequestException.invalid();
        }

        String value = values.get(0);
        if (value == null
                || value.length() > MAX_HEADER_CHARS
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw SelectionReplayRequestException.invalid();
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            throw SelectionReplayRequestException.invalid();
        }
        String version = value.substring(0, separator);
        if (!"v1".equals(version)) {
            throw SelectionReplayRequestException.unsupported();
        }
        String payload = value.substring(separator + 1);
        if (!PAYLOAD.matcher(payload).matches() || payload.indexOf('=') >= 0) {
            throw SelectionReplayRequestException.invalid();
        }

        byte[] decoded = null;
        try {
            decoded = Base64.getUrlDecoder().decode(payload);
            SelectionReplaySpec spec = SelectionReplaySpec.v1(decoded);
            return new Resolved(
                    SelectionEntropyFactory.replay(spec),
                    SelectionDecisionLedger.forReplay());
        } catch (SelectionEntropyException failure) {
            throw SelectionReplayRequestException.from(failure.reason());
        } catch (IllegalArgumentException failure) {
            throw SelectionReplayRequestException.invalid();
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    public record Resolved(SelectionEntropy entropy, SelectionDecisionLedger ledger) {

        public Resolved {
            Objects.requireNonNull(entropy, "entropy");
            Objects.requireNonNull(ledger, "ledger");
        }

        public static Resolved standard() {
            return new Resolved(
                    SelectionEntropyFactory.standard(),
                    SelectionDecisionLedger.forStandard());
        }

        @Override
        public String toString() {
            return "Resolved[mode=" + entropy.mode().wireValue() + "]";
        }
    }
}
