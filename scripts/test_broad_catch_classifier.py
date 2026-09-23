import tempfile
import unittest
from pathlib import Path

from broad_catch_classifier import CatchBlock, extract_catch_blocks


class BroadCatchClassifierTest(unittest.TestCase):

    def test_trace_telemetry_skipped_helper_is_breadcrumb(self):
        block = CatchBlock(
            "CancelShieldFuture.java",
            186,
            "catch (Throwable recordError) {",
            'traceTelemetrySkipped("record_cancel", recordError);',
        )

        self.assertEqual("HAS_BREADCRUMB", block.classify())

    def test_extracts_one_line_catch_body_before_classification(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "OneLineCatch.java"
            source.write_text(
                """
                class OneLineCatch {
                    void run() {
                        try {
                            throw new IllegalStateException();
                        } catch (Exception suppressed) { traceSuppressed("stage", suppressed); }
                    }
                }
                """,
                encoding="utf-8",
            )

            blocks = extract_catch_blocks(source)

        self.assertEqual(1, len(blocks))
        self.assertIn("traceSuppressed", blocks[0].body)
        self.assertEqual("HAS_BREADCRUMB", blocks[0].category)

    def test_extracts_catch_body_when_try_and_catch_share_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "InlineTryCatch.java"
            source.write_text(
                """
                class InlineTryCatch {
                    void run() {
                        try { local.put(key, value); } catch (RuntimeException ex) { traceSuppressed("fallback", ex); }
                    }
                }
                """,
                encoding="utf-8",
            )

            blocks = extract_catch_blocks(source)

        self.assertEqual(1, len(blocks))
        self.assertIn("traceSuppressed", blocks[0].body)
        self.assertNotIn("local.put", blocks[0].body)
        self.assertEqual("HAS_BREADCRUMB", blocks[0].category)

    def test_repo_local_trace_helpers_are_breadcrumbs(self):
        helper_calls = [
            'traceSkipped("stage", error);',
            'traceFailure("stage", error);',
            'traceCancelFailure(query, error);',
            'traceSearchPolicyFailure(query, "apply", error);',
            'traceChatSuppressed("stage", error);',
            'traceContextFallbackSkipped("stage", error);',
        ]

        for body in helper_calls:
            with self.subTest(body=body):
                block = CatchBlock(
                    "RepoLocalTraceHelpers.java",
                    10,
                    "catch (Exception error) {",
                    body,
                )

                self.assertEqual("HAS_BREADCRUMB", block.classify())

    def test_similar_trace_chat_name_is_not_a_breadcrumb(self):
        block = CatchBlock(
            "SimilarHelperName.java",
            10,
            "catch (Exception error) {",
            'notraceChatSuppressed("stage", error);',
        )

        self.assertEqual("GENUINE_SILENT", block.classify())

    def test_qualified_trace_chat_name_is_not_a_verified_breadcrumb(self):
        block = CatchBlock(
            "QualifiedHelperName.java",
            10,
            "catch (Exception error) {",
            'other.traceChatSuppressed("stage", error);',
        )

        self.assertEqual("GENUINE_SILENT", block.classify())

    def test_context_fallback_lookalikes_are_not_verified_breadcrumbs(self):
        bodies = [
            'traceContextFallbackSkippedButDoesNothing("stage", error);',
            'other.traceContextFallbackSkipped("stage", error);',
        ]

        for body in bodies:
            with self.subTest(body=body):
                block = CatchBlock(
                    "ContextFallbackLookalike.java",
                    10,
                    "catch (Exception error) {",
                    body,
                )

                self.assertEqual("GENUINE_SILENT", block.classify())

    def test_exact_logger_receivers_and_levels_are_breadcrumbs(self):
        for receiver in ("log", "LOG", "logger", "LOGGER"):
            for level in ("info", "warn", "error", "debug", "trace"):
                with self.subTest(receiver=receiver, level=level):
                    block = CatchBlock(
                        "LoggerBreadcrumb.java",
                        10,
                        "catch (Exception error) {",
                        f'{receiver}.{level} ("suppressed", error);',
                    )

                    self.assertEqual("HAS_BREADCRUMB", block.classify())

    def test_logger_receiver_lookalikes_and_non_calls_are_not_breadcrumbs(self):
        bodies = [
            'catalog.warn("suppressed", error);',
            'mylogger.error("suppressed", error);',
            'audit.trace("suppressed", error);',
            'other.log.warn("suppressed", error);',
            'holder.logger.error("suppressed", error);',
            'log.warn;',
            'LOGGER.errorCode;',
        ]

        for body in bodies:
            with self.subTest(body=body):
                block = CatchBlock(
                    "LoggerLookalike.java",
                    10,
                    "catch (Exception error) {",
                    body,
                )

                self.assertNotEqual("HAS_BREADCRUMB", block.classify())

    def test_rethrowing_catch_is_not_genuine_silent(self):
        block = CatchBlock(
            "PropagatingCatch.java",
            10,
            "catch (Throwable error) {",
            'err = error; throw error;',
        )

        self.assertEqual("INTENTIONAL_IGNORE", block.classify())

    def test_trace_suppression_classes_are_breadcrumbs(self):
        block = CatchBlock(
            "TraceSuppressionClass.java",
            10,
            "catch (Exception error) {",
            'WebFailSoftTraceSuppressions.trace("stage", error);',
        )

        self.assertEqual("HAS_BREADCRUMB", block.classify())

    def test_parse_and_probe_helpers_are_breadcrumbs(self):
        helper_calls = [
            'traceParseSkipped("meta_string", error);',
            'recordRunOnceFailure(error);',
            'recordFailure(error, "scorePair");',
        ]

        for body in helper_calls:
            with self.subTest(body=body):
                block = CatchBlock(
                    "ParseAndProbeHelpers.java",
                    10,
                    "catch (Exception error) {",
                    body,
                )

                self.assertEqual("HAS_BREADCRUMB", block.classify())

    def test_ignores_catches_inside_block_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "CommentedCatch.java"
            source.write_text(
                """
                class CommentedCatch {
                    void run() {
                        return;
                        /*
                        try {
                            risky();
                        } catch (Exception deadCode) {
                            // Future re-enable must record a breadcrumb here.
                        }
                        */
                    }
                }
                """,
                encoding="utf-8",
            )

            blocks = extract_catch_blocks(source)

        self.assertEqual([], blocks)

    def test_ignores_catch_syntax_inside_java_literals(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "LiteralCatch.java"
            source.write_text(
                '''
                class LiteralCatch {
                    String normal = "catch (Exception fake) { }";
                    String textBlock = """
                        catch (RuntimeException textFake) { }
                    """;

                    void run() {
                        try { risky(); }
                        catch (Exception actual) { traceSuppressed("stage", actual); }
                    }
                }
                ''',
                encoding="utf-8",
            )

            blocks = extract_catch_blocks(source)

        self.assertEqual(1, len(blocks))
        self.assertEqual(10, blocks[0].line)
        self.assertEqual("actual", blocks[0].var_name)
        self.assertEqual("HAS_BREADCRUMB", blocks[0].category)


if __name__ == "__main__":
    unittest.main()
