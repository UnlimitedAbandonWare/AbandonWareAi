package com.example.lms.infra.selection;

public enum SelectionEntropyReason {
    NONE(""),
    REPLAY_FORBIDDEN("selection_entropy_replay_forbidden"),
    REPLAY_INVALID("selection_entropy_replay_invalid"),
    ALGORITHM_UNSUPPORTED("selection_entropy_algorithm_unsupported"),
    REPLAY_INIT_FAILED("selection_entropy_replay_init_failed"),
    CONTEXT_MISSING("selection_entropy_context_missing"),
    COORDINATE_INVALID("selection_entropy_coordinate_invalid"),
    STABLE_KEY_MISSING("selection_entropy_stable_key_missing"),
    DERIVATION_INVALID("selection_entropy_derivation_invalid"),
    CANDIDATE_DRIFT("selection_entropy_candidate_drift"),
    DECISION_CAP_REACHED("selection_entropy_decision_cap_reached");

    private final String code;

    SelectionEntropyReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
