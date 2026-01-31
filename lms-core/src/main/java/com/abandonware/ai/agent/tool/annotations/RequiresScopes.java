package com.abandonware.ai.agent.tool.annotations;

import com.abandonware.ai.agent.tool.ToolScope;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;



/**
 * Indicates that the annotated tool requires one or more scopes to be
 * granted before it can be executed.  Scopes correspond to permissions
 * enumerated in {@link ToolScope}.  When a tool annotated with this
 * annotation is invoked, the {@link com.abandonware.ai.agent.tool.aspect.ToolScopeAspect}
 * will consult the {@link com.abandonware.ai.agent.consent.ConsentService} to
 * verify that all required scopes are present in the caller's consent
 * token; otherwise a consent challenge will be raised.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.annotations.RequiresScopes
 * Role: class
 * Dependencies: com.abandonware.ai.agent.tool.ToolScope
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.annotations.RequiresScopes
role: class
*/
interface RequiresScopes {
    ToolScope[] value();
}