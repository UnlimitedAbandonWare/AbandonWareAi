/* 
//* Extracted formula module for orchestration
//* Source zip: src111_mx.zip
//* Source path: src/main/java/com/example/lms/util/SoftmaxUtil.java
//* Extracted: 2025-10-20T15:26:37.154119Z
//*/
package com.example.lms.util;

import java.util.concurrent.ThreadLocalRandom;



/**
 * 수치적으로 안정적인 Softmax 계산과 확률 기반 샘플링을 제공하는 유틸리티 클래스입니다.
 * <p>
 * Softmax 함수는 "subtract-max trick"을 사용하여 오버플로우를 방지하고,
 * 온도(temperature) 파라미터를 통해 확률 분포의 집중도를 조절합니다.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: SoftmaxUtil
 * 역할(Role): Class
 * 소스 경로: addons/formulas_pack/addons/formulas/java/com/example/lms/util/SoftmaxUtil.java
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
// Module: com.example.lms.util.SoftmaxUtil
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.example.lms.util.SoftmaxUtil
role: config
*/
class SoftmaxUtil {

    private SoftmaxUtil() {}

    /**
     * 입력된 logit 배열에 대해 Softmax 함수를 계산합니다.
     *
     * @param logits      점수(logit) 배열
     * @param temperature 온도 T. 0에 가까울수록 가장 높은 점수에 확률이 집중되고, 높을수록 분포가 균등해집니다.
     * @return 각 인덱스에 해당하는 확률 값의 배열
     */
    public static double[] softmax(double[] logits, double temperature) {
        if (logits == null || logits.length == 0) {
            return new double[0];
        }
        // 온도는 0이 될 수 없으므로, 매우 작은 양수 값으로 보정합니다.
        double t = Math.max(1e-8, temperature);

        // 1. 오버플로우 방지를 위해 최대값을 찾습니다 (subtract-max trick).
        double max = Double.NEGATIVE_INFINITY;
        for (double v : logits) {
            if (v > max) {
                max = v;
            }
        }

        // 2. 각 logit에서 최대값을 뺀 후 온도로 나누고, exp를 취해 합산합니다.
        double sum = 0.0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            double z = Math.exp((logits[i] - max) / t);
            out[i] = z;
            sum += z;
        }

        // 3. 합계가 유효하지 않으면 (0, 무한대, NaN), 균등 분포로 대체합니다.
        if (sum <= 0.0 || Double.isInfinite(sum) || Double.isNaN(sum)) {
            double uniform = 1.0 / logits.length;
            java.util.Arrays.fill(out, uniform);
            return out;
        }

        // 4. 각 값을 총합으로 나누어 정규화합니다.
        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    /**
     * 주어진 확률 분포에 따라 아이템 배열에서 하나의 아이템을 샘플링합니다.
     *
     * @param items 아이템 배열
     * @param probs 각 아이템에 해당하는 확률 배열 (총합은 1.0이어야 함)
     * @param u     [0, 1) 범위의 랜덤 값
     * @return 샘플링된 아이템
     */
    public static <T> T sample(T[] items, double[] probs, double u) {
        if (items == null || probs == null || items.length == 0 || items.length != probs.length) {
            return null;
        }
        double acc = 0.0;
        for (int i = 0; i < probs.length; i++) {
            acc += probs[i];
            if (u <= acc) {
                return items[i];
            }
        }
        // 부동소수점 오류 등으로 합계가 1.0이 안 될 경우를 대비해 마지막 아이템을 반환합니다.
        return items[items.length - 1];
    }

    /**
     * 주어진 확률 분포에 따라 아이템 배열에서 하나의 아이템을 랜덤으로 샘플링합니다.
     *
     * @param items 아이템 배열
     * @param probs 각 아이템에 해당하는 확률 배열
     * @return 샘플링된 아이템
     */
    public static <T> T sample(T[] items, double[] probs) {
        return sample(items, probs, ThreadLocalRandom.current().nextDouble());
    }
}