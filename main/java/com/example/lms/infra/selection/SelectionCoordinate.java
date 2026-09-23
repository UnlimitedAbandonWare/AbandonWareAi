package com.example.lms.infra.selection;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

public record SelectionCoordinate(
        String decisionKey,
        String actorKey,
        long attemptOrdinal,
        long drawOrdinal) {

    private static final Pattern DECISION = Pattern.compile("[a-z][a-z0-9._-]{0,95}");
    private static final Pattern ACTOR = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,127}");

    public SelectionCoordinate {
        actorKey = actorKey == null ? null : actorKey.toLowerCase(Locale.ROOT);
        if (decisionKey == null || !DECISION.matcher(decisionKey).matches()
                || actorKey == null || !ACTOR.matcher(actorKey).matches()
                || attemptOrdinal < 0L || drawOrdinal < 0L) {
            throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
        }
    }

    byte[] encoded() {
        byte[] decision = decisionKey.getBytes(StandardCharsets.UTF_8);
        byte[] actor = actorKey.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + decision.length + 4 + actor.length + 16)
                .putInt(decision.length).put(decision)
                .putInt(actor.length).put(actor)
                .putLong(attemptOrdinal).putLong(drawOrdinal).array();
    }
}
