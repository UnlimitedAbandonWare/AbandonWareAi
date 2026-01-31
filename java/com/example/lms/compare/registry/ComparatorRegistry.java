package com.example.lms.compare.registry;

import com.example.lms.compare.api.ComparatorCalculator;
import com.example.lms.compare.hybrid.HybridComparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;




/**
 * Registry of comparator calculators. This lightweight registry maps
 * arbitrary keys (typically domain or intent identifiers) to concrete
 * {@link ComparatorCalculator} implementations. When no specific key
 * is registered the registry falls back to a shared {@link HybridComparator}.
 */
public class ComparatorRegistry {

    private final Map<String, ComparatorCalculator> registry = new ConcurrentHashMap<>();
    private final ComparatorCalculator defaultComparator = new HybridComparator();

    /**
     * Register a calculator for a given key.
     * @param key a non-null identifier (e.g. "generic")
     * @param comparator the calculator to associate
     */
    public void register(String key, ComparatorCalculator comparator) {
        if (key == null || comparator == null) {
            return;
        }
        registry.put(key, comparator);
    }

    /**
     * Look up a calculator by key. Returns the default comparator if no
     * specific mapping exists.
     * @param key domain or intent identifier
     * @return a calculator instance
     */
    public ComparatorCalculator lookup(String key) {
        if (key == null) {
            return defaultComparator;
        }
        return registry.getOrDefault(key, defaultComparator);
    }
}