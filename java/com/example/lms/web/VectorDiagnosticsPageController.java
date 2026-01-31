package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Lightweight ops page for inspecting vector/runtime diagnostics.
 *
 * <p>Served as an admin-only page (under /admin/*) so it naturally follows
 * existing security rules. The page itself calls the JSON endpoints:
 * <ul>
 *   <li>/api/vector/diagnostics</li>
 *   <li>/api/diagnostics/runtime</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin")
public class VectorDiagnosticsPageController {

    // MERGE_HOOK:PROJ_AGENT::VECTOR_DIAGNOSTICS_PAGE_V1
    @GetMapping("/vector-diagnostics")
    public String page() {
        return "vector-diagnostics";
    }
}
