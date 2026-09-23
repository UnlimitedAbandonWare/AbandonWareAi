package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSnapshotsPageTemplateTest {

    @Test
    void traceSnapshotsListShowsRedactedPathAndHtmlSnapshotState() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/trace-snapshots.html"),
                StandardCharsets.UTF_8);

        assertTrue(html.contains("<th class=\"text-left p-2\">pathHash</th>"));
        assertTrue(html.contains("<th class=\"text-left p-2\">html</th>"));
        assertTrue(html.contains("function pathSummary(snap)"));
        assertTrue(html.contains("function htmlSnapshotState(snap)"));
        assertTrue(html.contains("appendTextCell(tr, pathSummary(snap), 'mono');"));
        assertTrue(html.contains("appendTextCell(tr, htmlSnapshotState(snap), 'mono');"));
        assertTrue(html.contains("snap.pathHash, snap.pathLength"));
        assertFalse(html.contains("snap.path, snap.lastControlAction"));
        assertFalse(html.contains("appendTextCell(tr, snap.path"));
        assertFalse(html.contains("tr.innerHTML = `"));
    }
}
