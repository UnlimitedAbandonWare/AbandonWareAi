package com.example.lms.service.rag.handler;

/**
 * Legacy bridge interface (renamed) for {@link com.example.lms.service.rag.SelfAskWebSearchRetriever}.
 *
 * <p>This interface extends the new canonical SelfAskWebSearchRetriever to preserve
 * binary compatibility with classes that still reference the old package path.
 * It does not declare any additional methods, relying on the default
 * {@code retrieve(Query)} implementation provided by the parent interface.</p>
 *
 * @deprecated Use {@link com.example.lms.service.rag.SelfAskWebSearchRetriever} instead.
 */
@Deprecated
public interface SelfAskWebSearchRetrieverLegacy extends com.example.lms.service.rag.SelfAskWebSearchRetriever {
    // no additional methods; inherits askWeb([TODO]) and retrieve([TODO]) from parent interface
}