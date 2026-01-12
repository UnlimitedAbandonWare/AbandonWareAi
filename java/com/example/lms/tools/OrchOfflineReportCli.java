package com.example.lms.tools;

import com.example.lms.orchestration.OrchAutoReporter;
import com.example.lms.orchestration.OrchTextLogTraceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline reporter/CLI for EOR-style plain text logs.
 *
 * <p>Usage examples:
 * <pre>
 *   java -cp app.jar com.example.lms.tools.OrchOfflineReportCli EOR_A.txt
 *   java -cp app.jar com.example.lms.tools.OrchOfflineReportCli --md EOR_A.txt
 *   java -cp app.jar com.example.lms.tools.OrchOfflineReportCli --json --pretty EOR_A.txt
 * </pre>
 */
public final class OrchOfflineReportCli {

    private OrchOfflineReportCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            System.err.println("Usage: OrchOfflineReportCli [--json|--md] [--pretty] <EOR_LOG_FILE> [...]");
            System.exit(2);
            return;
        }

        boolean markdown = false;
        boolean pretty = true;

        List<String> files = new ArrayList<>();
        for (String a : args) {
            if (a == null || a.isBlank()) {
                continue;
            }
            if ("--md".equalsIgnoreCase(a) || "--markdown".equalsIgnoreCase(a)) {
                markdown = true;
                continue;
            }
            if ("--json".equalsIgnoreCase(a)) {
                markdown = false;
                continue;
            }
            if ("--pretty".equalsIgnoreCase(a)) {
                pretty = true;
                continue;
            }
            if ("--compact".equalsIgnoreCase(a)) {
                pretty = false;
                continue;
            }
            files.add(a);
        }

        if (files.isEmpty()) {
            System.err.println("No input files.");
            System.exit(2);
            return;
        }

        ObjectMapper om = new ObjectMapper();
        if (pretty) {
            om.enable(SerializationFeature.INDENT_OUTPUT);
        }

        boolean first = true;
        for (String f : files) {
            Path p = Paths.get(f);
            Map<String, String> trace = OrchTextLogTraceParser.parse(p);
            Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);

            if (!first) {
                System.out.println();
                System.out.println("---");
                System.out.println();
            }
            first = false;

            if (markdown) {
                System.out.println(OrchAutoReporter.renderText(report));
            } else {
                System.out.println(om.writeValueAsString(report));
            }
        }
    }
}
