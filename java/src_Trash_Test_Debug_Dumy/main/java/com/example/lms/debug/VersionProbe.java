package com.example.lms.debug;

import java.util.LinkedHashMap;
import java.util.Map;

public class VersionProbe {

    public static Map<String, String> langchain4j() {
        Map<String,String> res = new LinkedHashMap<>();
        try {
            Package p = dev.langchain4j.model.openai.OpenAiChatModel.class.getPackage();
            String v = p != null ? p.getImplementationVersion() : null;
            res.put("dev.langchain4j", v != null ? v : "<unknown>");
        } catch (Throwable t) {
            res.put("dev.langchain4j", "<not found>");
        }
        return res;
    }
}
