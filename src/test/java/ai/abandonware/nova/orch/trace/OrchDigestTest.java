package ai.abandonware.nova.orch.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrchDigestTest {

    @Test
    void canonicalDigestUsesArrayContentsInsteadOfObjectIdentity() {
        String first = OrchDigest.sha1Canonical(new String[]{"brave", "naver"});
        String second = OrchDigest.sha1Canonical(new String[]{"brave", "naver"});
        String reordered = OrchDigest.sha1Canonical(new String[]{"naver", "brave"});

        assertEquals(first, second);
        assertNotEquals(first, reordered);
    }

    @Test
    void canonicalDigestHandlesPrimitiveArraysByContent() {
        String first = OrchDigest.sha1Canonical(new int[]{1, 2, 3});
        String second = OrchDigest.sha1Canonical(new int[]{1, 2, 3});
        String changed = OrchDigest.sha1Canonical(new int[]{1, 3, 2});

        assertEquals(first, second);
        assertNotEquals(first, changed);
    }
}
