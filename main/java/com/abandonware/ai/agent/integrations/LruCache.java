
package com.abandonware.ai.agent.integrations;

import java.util.LinkedHashMap;
import java.util.Map;



/**
 * Simple LRU cache based on LinkedHashMap.
 * Thread-safe for simple get/put via synchronized methods.
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