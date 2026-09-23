import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class FaithfulnessPromptTraceContractTest(unittest.TestCase):
    def test_prompt_trace_publisher_contract(self):
        source = ROOT / "main/java/com/example/lms/metrics/FaithfulnessPromptTracePublisher.java"
        self.assertTrue(source.exists(), "FaithfulnessPromptTracePublisher.java must exist")
        text = source.read_text(encoding="utf-8")

        for key in [
            "rag.eval.normalized.retrievalHitRate",
            "rag.eval.normalized.evidenceCoverage",
            "rag.eval.normalized.resultDepth",
            "rag.eval.normalized.schemaVersion",
            "rag.answerQuality.decision",
            "rag.answerQuality.faithfulnessScore",
            "rag.answerQuality.docCount",
            "rag.answerQuality.distinctSources",
        ]:
            self.assertIn(key, text)

        self.assertIn("TraceStore.putIfAbsent", text)
        self.assertIn("FaithfulnessMetricSnapshotStore.put", text)
        self.assertIn("REPAIR_WITH_WEB", text)
        self.assertNotIn("rawPrompt", text)
        self.assertNotIn("Authorization", text)

    def test_chat_workflow_publishes_prompt_metrics_before_llm_call(self):
        source = ROOT / "main/java/com/example/lms/service/ChatWorkflow.java"
        text = source.read_text(encoding="utf-8")

        call = text.find("FaithfulnessPromptTracePublisher.publishBeforeLlm(")
        projection = text.find("RagFailureBlackboxService.projectCurrentTrace(", call)
        route = text.find("modelRouter.route(", call)
        primary_call = text.find("draft = callWithRetryReportingSuccess(", route)
        retry_wrapper = text.find("private String callWithRetryReportingSuccess(", primary_call)
        timed_chat = text.find("TimedChatModelCaller.chat(", retry_wrapper)

        self.assertGreater(call, 0, "ChatWorkflow must publish prompt metrics")
        self.assertGreater(projection, call, "scorecard projection should run after metric publish")
        self.assertGreater(route, projection, "metric publish must happen before model routing")
        self.assertGreater(primary_call, route, "metric publish must happen before the primary LLM call")
        self.assertGreater(retry_wrapper, primary_call, "primary call must use the retry wrapper")
        self.assertGreater(timed_chat, retry_wrapper, "retry wrapper must own the timed model invocation")


if __name__ == "__main__":
    unittest.main()
