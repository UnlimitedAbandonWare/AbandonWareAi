package com.example.lms.service.rag.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.plan.PlanDslLoader
 * Role: config
 * Dependencies: com.fasterxml.jackson.databind.ObjectMapper, com.fasterxml.jackson.dataformat.yaml.YAMLFactory
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.plan.PlanDslLoader
role: config
*/
public class PlanDslLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    public PlanProfile load(String name){
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plans/" + name + ".yaml")) {
            if (in == null) return null;
            return yaml.readValue(in, PlanProfile.class);
        } catch (Exception e){
            return null;
        }
    }
}