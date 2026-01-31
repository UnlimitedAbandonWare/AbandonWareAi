package com.example.lms.service.rag.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.lang.reflect.Field;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that validates the fixed retrieval chain order.  The
 * {@link com.example.lms.config.RetrieverChainConfig} constructs a chain
 * comprising a MemoryHandler followed by SelfAskHandler, AnalyzeHandler,
 * SearchCostGuardHandler, WebHandler and VectorDbHandler.  This test
 * introspects the private {@code next} field on {@link AbstractRetrievalHandler}
 * to traverse the chain and assert that each handler appears in the correct
 * sequence.  If the chain is misconfigured or a handler is missing the
 * assertions will fail.
 */
@SpringBootTest
public class ChainOrderFixedTest {

    @Autowired
    private RetrievalHandler retrievalHandler;

    @Test
    public void chainShouldFollowFixedOrder() throws Exception {
        // The first bean is a MemoryHandler which delegates to the first real handler.
        RetrievalHandler current = retrievalHandler;
        // Define the expected sequence after the memory handler.
        Class<?>[] expected = new Class<?>[] {
                SelfAskHandler.class,
                AnalyzeHandler.class,
                SearchCostGuardHandler.class,
                WebHandler.class,
                VectorDbHandler.class
        };
        // Access the private 'next' field via reflection.
        Field nextField = AbstractRetrievalHandler.class.getDeclaredField("next");
        nextField.setAccessible(true);
        // Walk the chain and assert the types match the expected order.
        for (Class<?> expectedClass : expected) {
            current = (RetrievalHandler) nextField.get(current);
            assertNotNull(current, "Expected handler " + expectedClass.getSimpleName() + " but chain terminated early");
            assertEquals(expectedClass, current.getClass(), "Handler order mismatch");
        }
        // Ensure the chain terminates after the final handler.
        RetrievalHandler tail = (RetrievalHandler) nextField.get(current);
        assertNull(tail, "Chain should terminate after VectorDbHandler");
    }
}