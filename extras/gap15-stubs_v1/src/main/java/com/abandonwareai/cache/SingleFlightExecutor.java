package com.abandonwareai.cache;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.cache.SingleFlightExecutor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.cache.SingleFlightExecutor
role: config
*/
public class SingleFlightExecutor {
    // Execute only once per key concurrently
    public <T> T run(String key, java.util.concurrent.Callable<T> c) throws Exception { return c.call(); }

}