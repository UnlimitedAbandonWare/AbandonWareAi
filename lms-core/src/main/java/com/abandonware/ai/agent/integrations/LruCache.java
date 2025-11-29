
package com.abandonware.ai.agent.integrations;

import java.util.LinkedHashMap;
import java.util.Map;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.LruCache
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.LruCache
role: config
*/
public class LruCache<K, V> extends LinkedHashMap<K, V> {

    private final int maxSize;

    public LruCache(int maxSize) {
        super(Math.max(16, maxSize), 0.75f, true);
        this.maxSize = maxSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return size() > maxSize;
    }

    public synchronized V getValue(K key) {
        return super.get(key);
    }

    public synchronized void putValue(K key, V value) {
        super.put(key, value);
    }

    public synchronized boolean containsKeyValue(K key) {
        return super.containsKey(key);
    }
}