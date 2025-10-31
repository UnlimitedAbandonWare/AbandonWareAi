package com.example.lms.service.help;

import com.example.lms.dto.help.ContextHelpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.Map;




/**
 * 최소 동작 보장용 기본 구현.
 * {스터프1}의 시스템 설명을 요약해 UI 요소별로 맥락 도움말을 제공합니다.
 */
@Slf4j
@Service
public class DefaultContextHelpService implements ContextHelpService {

        @Override
        public String getHelpFor(ContextHelpRequest request) {
                if (request == null) return "컨텍스트가 비어 있습니다.";

                String type = safeLower(request.getContextType());
                Map<String, Object> data = request.getContextData();

                String elementId = (data == null) ? "" : String.valueOf(data.getOrDefault("elementId", ""));

                // 간단한 규칙 기반 맥락 도움말
                switch (elementId) {
                        case "send-button":
                        case "sendBtn":
                                return """
                        입력창의 내용을 모델로 전송합니다.
                        • Enter=전송, Shift+Enter=줄바꿈
                        • 상단 '지능형 정보보강 모드'가 켜져 있으면 웹 검색과 벡터 RAG를 결합하여 컨텍스트를 구성합니다.
                        • 세션 히스토리가 저장 중이면 이전 메시지도 PromptContext에 포함됩니다.
                        """;
                        case "useRag":
                                return """
                        지능형 정보보강(RAG) 스위치입니다.
                        • WebHandler + VectorDbHandler 증거를 융합(RRF) 후 Cross-Encoder 재랭킹
                        • AuthorityScorer로 출처 신뢰도를 가중, HyperparameterService로 시너지 보너스 조정
                        """;
                        case "saveSettingsBtn":
                                return """
                        현재 슬라이더/옵션 값을 서버 설정으로 즉시 저장합니다.
                        • MAX_MEMORY_TOKENS, MAX_RAG_TOKENS, WEB_TOP_K 등은 런타임에 반영됩니다.
                        """;
                        default:
                                // 기본 설명(시스템 전반 요약)
                                return """
                        AbandonWare Hybrid RAG 에이전트는 검색→생성→검증→강화 루프를 따릅니다.
                        • HybridRetriever: Self-Ask → Analyze → Web → Vector 체인, 실패 시 부분 결과 반환
                        • PromptBuilder: 시스템/사용자/컨텍스트 섹션을 표준 템플릿으로 조립
                        • Rerank: 크로스엔코더 + 관계 규칙 + 시너지 보너스 + 출처 신뢰도 감쇠
                        • Verification: 증거 부족/모순/미지원 주장 제거, 안전한 답변만 유지
                        """;
                }
        }

        private static String safeLower(String s) {
                return s == null ? "" : s.toLowerCase(Locale.ROOT);
        }
}