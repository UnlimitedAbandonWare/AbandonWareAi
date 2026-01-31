package com.abandonware.ai.agent.orchestrator;

import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.consent.ConsentRequiredException;
import com.abandonware.ai.agent.observability.AgentMetrics;
import com.abandonware.ai.agent.observability.AgentTracer;
import com.abandonware.ai.agent.observability.SpanNames;
import org.slf4j.MDC;
import com.abandonware.ai.agent.policy.BudgetGuard;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.contract.SchemaRegistry;
import com.abandonware.ai.agent.contract.ValidationException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.abandonware.ai.agent.orchestrator.nodes.PlannerNode;
import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.abandonware.ai.agent.orchestrator.nodes.SynthNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.orchestrator.Orchestrator
 * Role: config
 * Dependencies: com.abandonware.ai.agent.consent.ConsentService, com.abandonware.ai.agent.consent.ConsentRequiredException, com.abandonware.ai.agent.observability.AgentMetrics, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.Orchestrator
role: config
*/
public class Orchestrator {

    private boolean evalWhen(Step step, Map<String,Object> input, ToolContext context){
        String w = step.getWhen();
        if (w == null || w.isBlank()) return true;
        Object v = resolveExpression(w, input, context);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return true; // fail-open
    }

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final ToolRegistry registry;
    private final ConsentService consentService;
    private final FlowDefinitionLoader loader;
    private final AgentTracer tracer;
    private final AgentMetrics metrics;
    private final BudgetGuard budgetGuard;
    private final ToolPolicyEnforcer policy;
    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public Orchestrator(ToolRegistry registry, ConsentService consentService, FlowDefinitionLoader loader) {
        this(registry, consentService, loader, new AgentTracer(), new AgentMetrics(),
                new BudgetGuard(), new ToolPolicyEnforcer(), null);
    }

    public Orchestrator(ToolRegistry registry, ConsentService consentService, FlowDefinitionLoader loader,
                        AgentTracer tracer, AgentMetrics metrics) {
        this(registry, consentService, loader, tracer, metrics, new BudgetGuard(), new ToolPolicyEnforcer(), null);
    }

    public Orchestrator(ToolRegistry registry, ConsentService consentService, FlowDefinitionLoader loader,
                        AgentTracer tracer, AgentMetrics metrics, BudgetGuard budgetGuard, ToolPolicyEnforcer policy, SchemaRegistry schemaRegistry) {
        this.registry = registry;
        this.consentService = consentService;
        this.loader = loader;
        this.tracer = tracer;
        this.metrics = metrics;
        this.budgetGuard = budgetGuard;
        this.policy = policy;
        this.schemaRegistry = schemaRegistry;
    }

    /** Legacy name retained for compatibility. */
    public Map<String,Object> runFlow(String flowName, Map<String,Object> input, ToolContext context) {
        return execute(flowName, input, context);
    }

    /**
     * Main entry point: executes the given flow with input and context.
     */
    
private long extractMaxTokens(FlowDefinition def){
    try {
        java.lang.reflect.Method m = def.getClass().getMethod("getDefaults");
        Object defaults = m.invoke(def);
        if(defaults instanceof java.util.Map<?,?> map){
            Object budget = ((java.util.Map<?,?>)defaults).get("budget");
            if(budget instanceof java.util.Map<?,?> b){
                Object mt = b.get("max_tokens");
                if(mt instanceof Number) return ((Number) mt).longValue();
            }
        }
    } catch (Exception ignore) {}
    return 0L;
}
private double extractMaxCostUsd(FlowDefinition def){
    try {
        java.lang.reflect.Method m = def.getClass().getMethod("getDefaults");
        Object defaults = m.invoke(def);
        if(defaults instanceof java.util.Map<?,?> map){
            Object budget = ((java.util.Map<?,?>)defaults).get("budget");
            if(budget instanceof java.util.Map<?,?> b){
                Object mc = b.get("max_cost_usd");
                if(mc instanceof Number) return ((Number) mc).doubleValue();
            }
        }
    } catch (Exception ignore) {}
    return 0.0;
}

    public Map<String,Object> execute(String flowName, Map<String,Object> input, ToolContext context) {
        java.util.List<java.util.Map<String,Object>> traceEvents = context.debugTrace() ? new java.util.ArrayList<>() : null;
        // Bind contextual information into the Mapped Diagnostic Context (MDC) so that
        // downstream log statements include the session and flow identifiers.  Null
        // values are normalised to "n/a".
        MDC.put("sessionId", safe(context.sessionId()));
        MDC.put("flow", flowName);
        try {
            FlowDefinition def = loader.load(flowName);
        long maxTokens = extractMaxTokens(def);
        double maxCost = extractMaxCostUsd(def);
            // Flow-level scopes
            List<ToolScope> required = def.getRequireScopes();
            if (!required.isEmpty()) {
                consentService.ensureGranted(context.consent(),
                        required.toArray(new ToolScope[0]), null);
            }

            Map<String,Object> state = new LinkedHashMap<>();

            // [merge156-A] K allocation advisory (opt-in): if caller didn't supply ${input.k_alloc}
            try {
                Object q0 = (input!=null && input.containsKey("query")) ? input.get("query") : (input!=null? input.get("q") : null);
                if (q0 instanceof String qStr) {
                    boolean needs = (input==null) || !input.containsKey("k_alloc");
                    if (needs) {
                        java.util.Map<String,Integer> _k = com.abandonware.ai.agent.orchestrator.KAllocRuntime
                                .compute(qStr, 24 /*baseK*/, 0.7 /*T*/, (String) (input!=null? input.getOrDefault("intent",""):""));
                        if (input != null) { try { input.put("k_alloc", _k); } catch (Exception ignore) {} }
                        state.put("k_alloc", _k);
                    }
                }
            } catch (Throwable ignore) { /* fail-soft */ }

            if (input != null) state.putAll(input);

            for (Step step : def.getSteps()) {
                if (step.getType() == null) continue;

                // Record the current step in MDC and emit a start event
                MDC.put("step", step.getType().name());
                log.info("step.start type={} ", step.getType());

                if (!evalWhen(step, input, context)) { continue; }
                switch (step.getType()) {
                    case PLAN -> { java.util.Map<String,Object> ev = null; long t0 = java.time.Instant.now().toEpochMilli(); if(traceEvents!=null){ev=new java.util.LinkedHashMap<>(); ev.put("step","PLAN"); ev.put("input", input);}
                        long t0 = java.time.Instant.now().toEpochMilli();
                        try (var span = tracer.start(SpanNames.PLAN)) {
                            metrics.incrementStep(SpanNames.PLAN);
                            Map<String,Object> out = new PlannerNode().run(input);
                            if (out != null) state.putAll(out);
                        } finally { if(traceEvents!=null){ ev.put("duration_ms", java.time.Instant.now().toEpochMilli()-t0); traceEvents.add(ev);}
                            metrics.recordLatency(SpanNames.PLAN, java.time.Instant.now().toEpochMilli() - t0);
                        }
                    }
                    case CRITIC -> { java.util.Map<String,Object> ev = null; long t0 = java.time.Instant.now().toEpochMilli(); if(traceEvents!=null){ev=new java.util.LinkedHashMap<>(); ev.put("step","CRITIC"); ev.put("input", state);}
                        long t0 = java.time.Instant.now().toEpochMilli();
                        try (var span = tracer.start(SpanNames.CRITIC)) {
                            metrics.incrementStep(SpanNames.CRITIC);
                            Map<String,Object> out = new CriticNode().run(state);
                            if (out != null) state.putAll(out);
                        } finally { if(traceEvents!=null){ ev.put("duration_ms", java.time.Instant.now().toEpochMilli()-t0); traceEvents.add(ev);}
                            metrics.recordLatency(SpanNames.CRITIC, java.time.Instant.now().toEpochMilli() - t0);
                        }
                    }
                    case SYNTH -> { java.util.Map<String,Object> ev = null; long t0 = java.time.Instant.now().toEpochMilli(); if(traceEvents!=null){ev=new java.util.LinkedHashMap<>(); ev.put("step","SYNTH"); ev.put("input", state);}
                        long t0 = java.time.Instant.now().toEpochMilli();
                        try (var span = tracer.start(SpanNames.SYNTH)) {
                            metrics.incrementStep(SpanNames.SYNTH);
                            Map<String,Object> out = new SynthNode().run(state);
                            if (out != null) state.putAll(out);
                        } finally { if(traceEvents!=null){ ev.put("duration_ms", java.time.Instant.now().toEpochMilli()-t0); traceEvents.add(ev);}
                            metrics.recordLatency(SpanNames.SYNTH, java.time.Instant.now().toEpochMilli() - t0);
                        }
                    }
                    case TOOL -> { java.util.Map<String,Object> ev = null; long t0 = java.time.Instant.now().toEpochMilli();
                        String toolId = step.getUses();
                        AgentTool tool = registry.get(toolId).orElse(null);
                    org.slf4j.MDC.put("tool", toolId);
                    if(traceEvents!=null){ ev=new java.util.LinkedHashMap<>(); ev.put("step","TOOL"); ev.put("tool", toolId);}
                        if (tool == null) {
                            log.warn("Tool not found: {}", toolId);
                            continue;
                        }
                        // Resolve args (simple ${input.*} and ${context.*} expressions)
                        Map<String,Object> toolInput = new HashMap<>();
                        for (Map.Entry<String,Object> e : step.getArgs().entrySet()) {
                            toolInput.put(e.getKey(), resolveExpression(e.getValue(), input, context));
                        }

                        // Schema validation (if available)
                    try {
                        if(schemaRegistry!=null){
                            JsonSchema s = schemaRegistry.schemaFor(toolId);
                            if(s!=null){
                                java.util.Set<ValidationMessage> errs = s.validate(jsonMapper.valueToTree(toolInput));
                                if(!errs.isEmpty()) throw new ValidationException(errs);
                            }
                        }
                    } catch (ValidationException vex){ throw vex; }
                    // Budget check (best-effort; no model info)
                        if (!budgetGuard.allow("n/a", maxCost, maxTokens)) {
                            log.warn("BudgetGuard denied tool call: {}", toolId);
                            continue;
                        }

                        // Consent will be enforced by the ToolScopeAspect as well, but we can pre-check
                        try {
                            consentService.ensureGranted(context.consent(), new ToolScope[]{}, null);
                        } catch (ConsentRequiredException cre) {
                            throw cre;
                        }

                        long t0 = java.time.Instant.now().toEpochMilli();
                        policy.beforeCall(toolId);
                        try (var span = tracer.start(SpanNames.tool(toolId))) {
                            metrics.incrementTool(toolId);
                            ToolResponse resp = null;
                            int attempts = 0;
                            int maxAttempts = step.getRetry() != null ? Math.max(1, step.getRetry().getMaxAttempts()) : 1;
                            long backoff = step.getRetry() != null ? Math.max(0, step.getRetry().getInitialMs()) : 0;
                            String mode = step.getRetry() != null ? step.getRetry().getMode() : "FIXED";
                            Exception lastEx = null;
                            while (attempts < maxAttempts) {
                                try {
                                    resp = tool.execute(new ToolRequest(toolInput, context));
                                    lastEx = null;
                                    break;
                                } catch (Exception exAttempt) {
                                    lastEx = exAttempt;
                                    attempts++;
                                    if (attempts >= maxAttempts) break;
                                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                                    if ("EXP".equalsIgnoreCase(mode)) { backoff = Math.max(1, backoff * 2); }
                                }
                            }
                            if (lastEx != null) throw lastEx;
                            if (resp != null && resp.data() != null) { if(traceEvents!=null){ ev.put("output", resp.data()); }
                                state.put(toolId, resp.data());
                                // Also flatten top-level keys for convenience
                                state.putAll(resp.data());
                            }
                        } catch (Exception ex) {
                            // Increment per-tool error counter and log full exception
                            metrics.incrementToolError(toolId);
                            log.error("Tool {} execution failed", toolId, ex);
                            throw new RuntimeException(ex);
                        } finally { if(traceEvents!=null){ ev.put("duration_ms", java.time.Instant.now().toEpochMilli()-t0); traceEvents.add(ev);}
                            metrics.recordLatency(SpanNames.tool(toolId), java.time.Instant.now().toEpochMilli() - t0);
                            policy.afterCall(toolId);
                        }
                    }
                }
            }
            if(traceEvents!=null) state.put("trace", traceEvents);
        return state;
        } catch (Exception ex) {
            // Top-level exception handler: log the full stack trace before propagating
            log.error("orchestrator failed", ex);
            throw ex;
        } finally { if(traceEvents!=null){ ev.put("duration_ms", java.time.Instant.now().toEpochMilli()-t0); traceEvents.add(ev);}
            // Always clear MDC to avoid leaking context between requests
            MDC.clear();
        }
    }

    /**
     * Returns a non-null string representation of the provided value.  If the
     * input is null, "n/a" is returned instead.  MDC does not permit null
     * values.
     */
    private static String safe(Object value) {
        return value == null ? "n/a" : value.toString();
    }

    private Object resolveExpression(Object v, Map<String,Object> input, ToolContext context) {
        if (!(v instanceof String s)) return v;
        String expr = s;
        if (expr.startsWith("${") && expr.endsWith("}")) {
            String body = expr.substring(2, expr.length()-1);
            if (body.startsWith("input.")) {
                return input != null ? input.get(body.substring("input.".length())) : null;
            } else if (body.equals("context.sessionId")) {
                return context.sessionId();
            } else if (body.startsWith("context.")) {
                return context.extras().get(body.substring("context.".length()));
            }
        }
        return v;
    }
}