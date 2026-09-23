package com.example.lms.api;

import com.example.lms.infra.selection.SelectionEntropyReason;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class SelectionReplayRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private SelectionReplayRequestException(HttpStatus status, String code) {
        super(Objects.requireNonNull(code, "code"));
        this.status = Objects.requireNonNull(status, "status");
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    static SelectionReplayRequestException forbidden() {
        return new SelectionReplayRequestException(
                HttpStatus.FORBIDDEN,
                SelectionEntropyReason.REPLAY_FORBIDDEN.code());
    }

    static SelectionReplayRequestException invalid() {
        return new SelectionReplayRequestException(
                HttpStatus.BAD_REQUEST,
                SelectionEntropyReason.REPLAY_INVALID.code());
    }

    static SelectionReplayRequestException unsupported() {
        return new SelectionReplayRequestException(
                HttpStatus.BAD_REQUEST,
                SelectionEntropyReason.ALGORITHM_UNSUPPORTED.code());
    }

    static SelectionReplayRequestException initializationFailed() {
        return new SelectionReplayRequestException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                SelectionEntropyReason.REPLAY_INIT_FAILED.code());
    }

    static SelectionReplayRequestException from(SelectionEntropyReason reason) {
        if (reason == SelectionEntropyReason.REPLAY_FORBIDDEN) {
            return forbidden();
        }
        if (reason == SelectionEntropyReason.ALGORITHM_UNSUPPORTED) {
            return unsupported();
        }
        if (reason == SelectionEntropyReason.REPLAY_INIT_FAILED) {
            return initializationFailed();
        }
        return invalid();
    }
}
