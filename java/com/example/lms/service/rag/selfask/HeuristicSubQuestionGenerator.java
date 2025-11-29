package com.example.lms.service.rag.selfask;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class HeuristicSubQuestionGenerator implements SubQuestionGenerator {
    @Override
    public List<SubQuestion> generate(String q, Map<String,Object> ctx) {
        if (q == null) q = "";
        List<SubQuestion> lst = new ArrayList<>();
        String defQ = "정확한 정의/범위를 명시: " + q + " 의 핵심 용어 정의와 KPI/범위를 알려줘";
        String aliasQ = "동의어·약어·오타를 포괄: " + q + " 관련 별칭/약어/동의어/로마자 표기 포함하여 질의";
        String relQ = "원인-결과·구성요소·연관 시스템 관점에서: " + q + " 의 관계/가설/연관 규정 탐색";
        lst.add(new SubQuestion(SubQuestion.Type.DEFINITION, defQ, "heuristic"));
        lst.add(new SubQuestion(SubQuestion.Type.ALIAS, aliasQ, "heuristic"));
        lst.add(new SubQuestion(SubQuestion.Type.RELATION, relQ, "heuristic"));
        return lst;
    }
}