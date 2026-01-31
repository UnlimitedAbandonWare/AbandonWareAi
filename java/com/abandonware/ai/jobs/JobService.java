package com.abandonware.ai.jobs;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobService {
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    public String enqueue(String payload) {
        String id = UUID.randomUUID().toString();
        store.put(id, payload);
        return id;
    }
}