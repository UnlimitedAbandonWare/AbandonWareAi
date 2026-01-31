package com.example.lms.gptapi.filesearch;
import com.example.lms.gptapi.client.GptApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;





/**
 * Client for performing file search queries against the GPT API.
 *
 * <p>This class delegates to {@link GptApiClient} to perform the actual
* HTTP request.  In this shim implementation it constructs a request
 * payload containing the query text and desired top-k value but does
 * not execute any remote call.  Instead, it returns a static list
 * containing a single shim snippet.  In a real system you would
 * deserialize the response into structured snippet objects.</p>
 */
@Component
@RequiredArgsConstructor
public class GptFileSearchClient {
    private static final Logger log = LoggerFactory.getLogger(GptFileSearchClient.class);


    private final GptApiClient apiClient;

    /**
     * Execute a search against uploaded files using the GPT API.  If
     * remote invocation is disabled or unavailable, a single dummy
     * snippet will be returned.
     *
     * @param query the user query
     * @param topK  the maximum number of snippets to return
     * @return a list of snippet strings
     */
    public List<String> search(String query, int topK) {
        try {
            Map<String, Object> params = Map.of(
                    "query", query,
                    "top_k", topK
            );
            // Attempt to call the API; discard the result in this shim
            apiClient.post("/v1/file-search", params);
        } catch (Exception ex) {
            log.warn("File search API call failed: {}", ex.toString());
        }
        // Return a shim snippet indicating that file search is
        // configured.  Downstream components will treat an empty list as
        // no results; by returning a non-empty list we surface the fact
        // that the feature was enabled.
        List<String> out = new ArrayList<>();
        out.add("(파일 검색 결과 없음)");
        return out;
    }
}