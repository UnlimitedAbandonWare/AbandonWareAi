package com.abandonware.ai.agent.tool.aspect;

import com.abandonware.ai.agent.consent.ConsentRequiredException;
import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.aspect.ToolScopeAspect
 * Role: config
 * Dependencies: com.abandonware.ai.agent.consent.ConsentRequiredException, com.abandonware.ai.agent.consent.ConsentService, com.abandonware.ai.agent.tool.AgentTool, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.aspect.ToolScopeAspect
role: config
*/
public class ToolScopeAspect {
    private final ConsentService consentService;

    public ToolScopeAspect(ConsentService consentService) {
        this.consentService = consentService;
    }

    @Around("execution(* com.abandonware.ai.agent.tool.AgentTool.execute(..)) && target(tool)")
    public Object enforceScopes(ProceedingJoinPoint pjp, AgentTool tool) throws Throwable {
        RequiresScopes annotation = tool.getClass().getAnnotation(RequiresScopes.class);
        Object[] args = pjp.getArgs();
        if (annotation != null && args.length > 0 && args[0] instanceof ToolRequest req) {
            consentService.ensureGranted(req.context().consent(), annotation.value(), req.context().toConsentContext());
        }
        return pjp.proceed();
    }
}