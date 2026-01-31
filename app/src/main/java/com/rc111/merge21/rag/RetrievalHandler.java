
package com.rc111.merge21.rag;

import java.util.List;

public interface RetrievalHandler {
    String getName();
    List<SearchDoc> search(String query, DynamicRetrievalHandlerChain.Hint hints);
}
