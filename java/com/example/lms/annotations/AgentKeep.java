// src/main/java/com/example/lms/annotations/AgentKeep.java
package com.example.lms.annotations;

import java.lang.annotation.*;



/**
 * Marker to indicate the annotated program element must not be removed
 * or inlined by automated refactoring or "agent mode" optimizers.
 * This annotation has SOURCE retention and is used in conjunction with
 * repository policies to preserve debugging, diagnostics and telemetry code.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface AgentKeep {}