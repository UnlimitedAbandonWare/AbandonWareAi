package com.abandonware.ai.agent.orchestrator.recovery;

import com.abandonware.ai.agent.tool.request.ToolContext;
import java.util.Map;

public interface RecoveryExecutor {
    Map<String, Object> apply(RecoveryAction action,
                              Verdict verdict,
                              Map<String, Object> state,
                              ToolContext context);
}
