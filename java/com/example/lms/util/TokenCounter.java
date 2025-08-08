// 경로: com/example/lms/util/TokenCounter.java
package com.example.lms.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

@Component
public class TokenCounter {
    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }
}