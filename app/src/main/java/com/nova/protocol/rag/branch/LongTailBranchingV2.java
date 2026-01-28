package com.nova.protocol.rag.branch;

import java.util.ArrayList;
import java.util.List;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: LongTailBranchingV2
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/nova/protocol/rag/branch/LongTailBranchingV2.java
 *
 * 연결 포인트(Hooks):
 *   - DI/협력 객체는 @Autowired/@Inject/@Bean/@Configuration 스캔으로 파악하세요.
 *   - 트레이싱 헤더: X-Request-Id, X-Session-Id (존재 시 전체 체인에서 전파).
 *
 * 과거 궤적(Trajectory) 추정:
 *   - 본 클래스가 속한 모듈의 변경 이력은 /MERGELOG_*, /PATCH_NOTES_*, /CHANGELOG_* 문서를 참조.
 *   - 동일 기능 계통 클래스: 같은 접미사(Service/Handler/Controller/Config) 및 동일 패키지 내 유사명 검색.
 *
 * 안전 노트: 본 주석 추가는 코드 실행 경로를 변경하지 않습니다(주석 전용).
 */

public final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.nova.protocol.rag.branch.LongTailBranchingV2
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.nova.protocol.rag.branch.LongTailBranchingV2
role: config
*/
class LongTailBranchingV2 {

    public static final class SubQ {
        public final String type;
        public final String text;
        public final double weight;
        public SubQ(String t, String x, double w){ this.type=t; this.text=x; this.weight=w; }
    }

    /** 질문 1개 → (정의/도메인, 별칭/동의어, 관계/가설) 3축으로 분기 + 가중치 */
    public List<SubQ> expand(String q){
        if (q == null || q.isBlank()) return java.util.Collections.emptyList();
        double wDef = 0.40, wAlias = 0.30, wRel = 0.30;
        List<SubQ> out = new ArrayList<>(3);
        out.add(new SubQ("domain:def", "정의/배경: " + q + "의 핵심 개념·KPI·스펙은?", wDef));
        out.add(new SubQ("alias:syn",  "동의어/별칭/오타 보정: " + q + "는 또 어떻게 불리나?", wAlias));
        out.add(new SubQ("rel:hypo",   "관계/가설: " + q + "와(과) 인과·상관·버전관계는?", wRel));
        return out;
    }
}