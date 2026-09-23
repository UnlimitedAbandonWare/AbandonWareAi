package com.acme.aicore.domain.ports;

import reactor.core.publisher.Mono;



/**
 * Simple NLP port intended for lightweight tasks such as rewriting or
 * summarising text.  Unlike the full chat model port this interface
 * exposes a single method that accepts a plain input string and returns
 * a transformed string.  Implementations can delegate to small models
 * optimised for speed.
 */
public interface LightweightNlpPort {
    /**
     * Rewrite or summarise the given input string.  The exact behaviour
     * depends on the implementation and may include paraphrasing,
     * summarisation or language detection.
     *
     * @param input the text to transform
     * @return a Mono emitting the rewritten text
     */
    Mono<String> rewrite(String input);
}