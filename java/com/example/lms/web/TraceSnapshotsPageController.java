package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Lightweight ops page for inspecting in-memory trace snapshots.
 */
@Controller
@RequestMapping("/admin")
public class TraceSnapshotsPageController {

    // MERGE_HOOK:PROJ_AGENT::TRACE_SNAPSHOTS_PAGE_V1
    @GetMapping("/trace-snapshots")
    public String page() {
        return "trace-snapshots";
    }
}
