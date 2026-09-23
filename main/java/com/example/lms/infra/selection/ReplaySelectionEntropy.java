package com.example.lms.infra.selection;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ReplaySelectionEntropy implements SelectionEntropy {

    public static final String ALGORITHM_VERSION = "selection-entropy-v1";

    private static final byte[] FINGERPRINT_LABEL =
            "awx-selection-fingerprint-v1".getBytes(StandardCharsets.US_ASCII);
    private static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);
    private static final double TWO_53 = 9_007_199_254_740_992.0d;

    private final byte[] seed;
    private final String seedFingerprint;
    private final BlockDeriver deriver;

    @FunctionalInterface
    interface BlockDeriver {
        byte[] derive(SelectionCoordinate coordinate, int blockOrdinal);
    }

    public ReplaySelectionEntropy(byte[] seed) {
        this(seed, null);
    }

    ReplaySelectionEntropy(byte[] seed, BlockDeriver testDeriver) {
        if (seed == null || seed.length < 16 || seed.length > 64) {
            throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INVALID);
        }
        this.seed = Arrays.copyOf(seed, seed.length);
        this.seedFingerprint = fingerprint(this.seed);
        this.deriver = testDeriver == null ? this::deriveHmacBlock : testDeriver;
    }

    @Override
    public SelectionEntropyMode mode() {
        return SelectionEntropyMode.REPLAY;
    }

    @Override
    public String algorithmVersion() {
        return ALGORITHM_VERSION;
    }

    public String seedFingerprint() {
        return seedFingerprint;
    }

    @Override
    public double unitInterval(SelectionCoordinate coordinate) {
        byte[] digest = checkedDigest(coordinate, 0);
        long first53 = ByteBuffer.wrap(digest, 0, 8).getLong() >>> 11;
        double value = first53 / TWO_53;
        if (!Double.isFinite(value) || value < 0.0d || value >= 1.0d) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        return value;
    }

    @Override
    public int boundedIndex(SelectionCoordinate coordinate, int bound) {
        if (bound < 1 || bound > 1_000_000) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        BigInteger divisor = BigInteger.valueOf(bound);
        BigInteger limit = TWO_256.subtract(TWO_256.mod(divisor));
        for (int block = 0; block < 4; block++) {
            BigInteger value = new BigInteger(1, checkedDigest(coordinate, block));
            if (value.compareTo(limit) < 0) {
                return value.mod(divisor).intValueExact();
            }
        }
        throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
    }

    static String fingerprint(byte[] seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(FINGERPRINT_LABEL);
            digest.update((byte) 0);
            byte[] fingerprint = digest.digest(seed);
            return HexFormat.of().formatHex(fingerprint, 0, 6);
        } catch (GeneralSecurityException failure) {
            throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INIT_FAILED);
        }
    }

    private byte[] checkedDigest(SelectionCoordinate coordinate, int blockOrdinal) {
        if (coordinate == null || blockOrdinal < 0) {
            throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
        }
        byte[] digest = deriver.derive(coordinate, blockOrdinal);
        if (digest == null || digest.length != 32) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        return digest;
    }

    private byte[] deriveHmacBlock(SelectionCoordinate coordinate, int blockOrdinal) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            byte[] label = ALGORITHM_VERSION.getBytes(StandardCharsets.UTF_8);
            byte[] encoded = coordinate.encoded();
            return mac.doFinal(ByteBuffer.allocate(4 + label.length + encoded.length + 4)
                    .putInt(label.length).put(label)
                    .put(encoded)
                    .putInt(blockOrdinal)
                    .array());
        } catch (GeneralSecurityException failure) {
            throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INIT_FAILED);
        }
    }
}
