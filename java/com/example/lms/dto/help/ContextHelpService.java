package com.example.lms.service.help;

import com.example.lms.dto.help.ContextHelpRequest;



public interface ContextHelpService {
        /**
         * 주어진 UI/도메인 컨텍스트에 맞는 도움말 텍스트를 생성한다.
         * 초기 버전은 규칙 기반/정적 조합이며, 향후 RAG/LLM 연계 가능.
         */
        String getHelpFor(ContextHelpRequest request);
}