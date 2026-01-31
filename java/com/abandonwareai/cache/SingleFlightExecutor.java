package com.abandonwareai.cache;

import org.springframework.stereotype.Component;

@Component
public class SingleFlightExecutor {
    // Execute only once per key concurrently
    public <T> T run(String key, java.util.concurrent.Callable<T> c) throws Exception { return c.call(); }

}