package com.example.lms.transform;

import static org.junit.jupiter.api.Assertions.*;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class QueryTransformerFanoutRegressionTest {

    @Test
    void cheapVariantsFallback_shouldCapTo2_whenAuxIsDown() throws Exception {
        // Arrange: aux degraded => hard cap fan-out to 2
        var gc = new GuardContext();
        gc.setAuxDegraded(true);
        GuardContextHolder.set(gc);

        try {
            ChatModel dummyModel = (ChatModel) Proxy.newProxyInstance(
                    ChatModel.class.getClassLoader(),
                    new Class[] { ChatModel.class },
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("ChatModel should not be invoked by fallback");
                    });

            var qt = new QueryTransformer(dummyModel);

            // Force cheapVariants=3 (was the source of the 3-query burst in logs)
            Field cheapVariants = QueryTransformer.class.getDeclaredField("cheapVariants");
            cheapVariants.setAccessible(true);
            cheapVariants.setInt(qt, 3);

            Method m = QueryTransformer.class.getDeclaredMethod("cheapVariantsFallback", String.class, String.class);
            m.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> variants = (List<String>) m.invoke(qt, "갤럭시 트라이 폴드 사양좀 알려줘봐.", null);

            // Assert: hard cap to 2
            assertTrue(variants.size() <= 2, "expected fan-out <=2 but got: " + variants);
            assertFalse(variants.stream().anyMatch(v -> v.trim().equals("알려줘봐")), "should not emit bare polite token");
        } finally {
            GuardContextHolder.clear();
        }
    }
}
