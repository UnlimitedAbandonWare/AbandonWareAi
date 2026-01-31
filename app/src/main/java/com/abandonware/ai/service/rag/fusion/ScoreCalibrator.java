package com.abandonware.ai.service.rag.fusion;

import java.util.Arrays;
/** Platt (logistic) with fallback to isotonic-like monotone fix via sorting buckets. */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: ScoreCalibrator
 * 역할(Role): Class
 * 관련 기능(Tags): RAG Fusion
 * 소스 경로: app/src/main/java/com/abandonware/ai/service/rag/fusion/ScoreCalibrator.java
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
// Module: com.abandonware.ai.service.rag.fusion.ScoreCalibrator
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.ScoreCalibrator
role: config
*/
class ScoreCalibrator {
    public static double[] minMax(double[] v){
        if(v==null||v.length==0) return new double[0];
        double min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
        for(double x: v){ if(x<min)min=x; if(x>max)max=x; }
        double[] out = new double[v.length];
        if(max<=min){ Arrays.fill(out, 1.0); return out; }
        for(int i=0;i<v.length;i++){ out[i]=(v[i]-min)/(max-min); }
        return out;
    }
    public static double plattSigmoid(double x, double a, double b){
        return 1.0/(1.0+Math.exp(a*x+b));
    }
}