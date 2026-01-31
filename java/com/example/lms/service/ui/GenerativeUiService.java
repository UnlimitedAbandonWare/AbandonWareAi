package com.example.lms.service.ui;


/**
 * Service interface for rendering generative UI specifications to safe HTML.
 *
 * <p>Implementations must sanitize any generated HTML to prevent injection and
 * ensure it conforms to the application's UI guidelines.  This interface
 * intentionally does not prescribe how the sanitized HTML is delivered (e.g.,
 * via SSE).  Other components (e.g., controllers or emitters) should be
 * responsible for delivering the rendered UI to clients.</p>
 */
public interface GenerativeUiService {

    /**
     * Convert a JSON UI specification into a sanitized HTML fragment.
     *
     * @param jsonSpec a JSON string describing UI components
     * @return a sanitized HTML representation
     */
    String toSafeHtml(String jsonSpec);
}