package com.example.lms.cfvm.tools;

import com.example.lms.cfvm.build.BuildErrorReporter;

import java.nio.file.Path;
import java.util.UUID;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.tools.ScanBuildLogMain
 * Role: config
 * Dependencies: com.example.lms.cfvm.build.BuildErrorReporter
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.tools.ScanBuildLogMain
role: config
*/
public class ScanBuildLogMain {
    public static void main(String[] args) throws Exception {
        String log = null, out = "build-logs/build-errors.ndjson", summary = "build-logs/build-error-summary.json", session = null;
        for (int i=0;i<args.length;i++) {
            switch (args[i]) {
                case "--log": log = args[++i]; break;
                case "--out": out = args[++i]; break;
                case "--summary": summary = args[++i]; break;
                case "--session": session = args[++i]; break;
            }
        }
        if (log == null) {
            System.err.println("Usage: --log <build log file> [--out build-logs/build-errors.ndjson] [--summary build-logs/build-error-summary.json] [--session <id>]");
            System.exit(2);
        }
        if (session == null) session = UUID.randomUUID().toString();
        BuildErrorReporter reporter = new BuildErrorReporter();
        BuildErrorReporter.Result res = reporter.reportFromLog(Path.of(log), Path.of(out), Path.of(summary), session);
        System.out.println("Build error scan complete. slots=" + res.total + " codes=" + res.byCode);
    }
}