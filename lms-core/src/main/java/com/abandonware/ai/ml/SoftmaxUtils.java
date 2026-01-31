package com.abandonware.ai.ml;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: SoftmaxUtils
 * 역할(Role): Class
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/ml/SoftmaxUtils.java
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
// Module: com.abandonware.ai.ml.SoftmaxUtils
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.ml.SoftmaxUtils
role: config
*/
class SoftmaxUtils {
    private SoftmaxUtils(){}

    public static double[] stableSoftmax(double[] logits, double temperature){
        double t = temperature <= 0 ? 1.0 : temperature;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : logits) if (v > max) max = v;
        double[] exp = new double[logits.length];
        double sum = 0.0;
        for (int i=0;i<logits.length;i++){
            exp[i] = Math.exp((logits[i] - max)/t);
            sum += exp[i];
        }
        if (sum <= 0) {
            double[] uniform = new double[logits.length];
            double u = logits.length==0?0:1.0/logits.length;
            for (int i=0;i<logits.length;i++) uniform[i] = u;
            return uniform;
        }
        for (int i=0;i<exp.length;i++) exp[i] /= sum;
        return exp;
    }
}