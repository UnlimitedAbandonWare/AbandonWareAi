package com.example.lms;

import com.example.lms.matrix.MatrixTransformer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.Set;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test to verify that the MatrixTransformer tokenization preserves certain
 * punctuation characters such as plus signs (+), hyphens/dashes, apostrophes and
 * slashes.  Earlier versions of the transformer stripped these symbols, leading
 * to loss of semantic distinctions (e.g. "a/b+c" collapsed to "abc").  This
 * test uses reflection to access the private toTokens() method and asserts
 * that compound tokens are retained intact.
 */
public class MatrixTransformerTest {

    @Test
    public void testTokenizationPreservesSpecialCharacters() throws Exception {
        // Access the private static method toTokens via reflection
        Method m = MatrixTransformer.class.getDeclaredMethod("toTokens", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> tokens = (Set<String>) m.invoke(null, "jean-paul's a/b+c");
        assertTrue(tokens.contains("jean-paul's"), "Compound name with hyphen and apostrophe should be preserved");
        assertTrue(tokens.contains("a/b+c"), "Compound expression with slash and plus should be preserved");
    }
}