package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Lightweight ops page for inspecting in-memory DebugEventStore events.
 *
 * <p>Served under /admin/* so it naturally follows existing security rules.
 * The page calls the JSON endpoints:
 * <ul>
 *   <li>/api/diagnostics/debug/events</li>
 *   <li>/api/diagnostics/debug/fingerprints</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin")
public class DebugEventsPageController {

    // MERGE_HOOK:PROJ_AGENT::DEBUG_EVENTS_PAGE_V1
    @GetMapping("/debug-events")
    public String page() {
        return "debug-events";
    }
}
