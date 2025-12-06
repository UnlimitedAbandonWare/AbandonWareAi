package com.example.lms.service.rag.selfask;

import java.util.List;
import java.util.Map;

public interface SubQuestionGenerator {
    List<SubQuestion> generate(String userQuery, Map<String,Object> ctx);
}