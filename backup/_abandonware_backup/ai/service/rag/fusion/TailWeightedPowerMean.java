package com.abandonware.ai.service.rag.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Tail-weighted power mean aggregator (for hyper-risk modes).
 * Uses top-α fraction of signals then applies WPM(p).
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: TailWeightedPowerMean
 * 역할(Role): Class
 * 관련 기능(Tags): RAG Fusion
 * 소스 경로: src/main/java/_abandonware_backup/ai/service/rag/fusion/TailWeightedPowerMean.java
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
// Module: com.abandonware.ai.service.rag.fusion.TailWeightedPowerMean
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.TailWeightedPowerMean
role: config
*/
class TailWeightedPowerMean {

    public double aggregate(List<Double> xs, double alpha, double p){
        if (xs == null || xs.isEmpty()) return 0.0d;
        List<Double> sorted = new ArrayList<>(xs);
        sorted.sort(Comparator.reverseOrder());
        int k = Math.max(1, (int) Math.ceil(alpha * sorted.size()));
        List<Double> head = sorted.subList(0, k);
        List<Double> ws = new ArrayList<>(Collections.nCopies(head.size(), 1.0d));
        return new WpmFuser().wpm(head, ws, p);
    }
}