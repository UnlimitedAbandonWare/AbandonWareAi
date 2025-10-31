package service.rag.rerank;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleBiFunction;

/**
 * DPP 기반 다양성 재랭커.
 * - 기본형: T가 HasVectorAndScore<T>를 구현한 경우
 * - 오버로드: 벡터/스코어 추출 람다를 주입하는 경우
 */
public class DppDiversityReranker {

    /** 최소 요구 능력: 벡터와 상대 점수 제공 */
    public interface HasVectorAndScore<T> {
        float[] getVector();
        double getScoreFrom(T other);
    }

    /** 기본형: T가 인터페이스를 구현하는 경우 */
    public static <T extends HasVectorAndScore<T>> List<T> rerank(List<T> in) {
        return rerank(
            in,
            T::getVector,
            (a, b) -> a.getScoreFrom(b)
        );
    }

    /** 오버로드: 외부 타입에 대한 어댑터 제공(람다 주입) */
    public static <T> List<T> rerank(
            List<T> in,
            Function<T, float[]> vectorOf,
            ToDoubleBiFunction<T, T> scoreFrom
    ) {
        if (in == null || in.isEmpty()) return in;

        final int n = in.size();
        final double[] quality = new double[n];
        final double[][] kernel = new double[n][n];

        // 품질(관련성) 추정: 첫 요소를 기준으로 예시 구현
        for (int i = 0; i < n; i++) {
            double q = scoreFrom.applyAsDouble(in.get(i), in.get(0));
            quality[i] = Math.max(q, 1e-9); // 수치 안정화
        }

        // 커널 K = q_i q_j * cos(v_i, v_j)
        for (int i = 0; i < n; i++) {
            float[] vi = vectorOf.apply(in.get(i));
            for (int j = i; j < n; j++) {
                float[] vj = vectorOf.apply(in.get(j));
                double sim = cosine(vi, vj);
                double val = quality[i] * quality[j] * sim;
                kernel[i][j] = val;
                kernel[j][i] = val;
            }
        }

        List<Integer> selected = greedyDpp(kernel);
        List<T> out = new ArrayList<>(selected.size());
        for (int idx : selected) out.add(in.get(idx));
        return out;
    }

    /** Schur 보정 근사 기반 그리디 DPP */
    private static List<Integer> greedyDpp(double[][] K) {
        final int n = K.length;
        final boolean[] chosen = new boolean[n];
        final double[] gain = new double[n];
        final List<Integer> order = new ArrayList<>(n);

        for (int i = 0; i < n; i++) gain[i] = Math.max(K[i][i], 0.0);

        for (int step = 0; step < n; step++) {
            int best = -1;
            double bestGain = -1.0;
            for (int i = 0; i < n; i++) {
                if (!chosen[i] && gain[i] > bestGain) {
                    bestGain = gain[i];
                    best = i;
                }
            }
            if (best == -1 || bestGain <= 0) break;

            chosen[best] = true;
            order.add(best);

            double kbb = K[best][best] + 1e-9; // 안정화
            for (int i = 0; i < n; i++) {
                if (!chosen[i]) {
                    double kij = K[i][best];
                    gain[i] -= (kij * kij) / kbb;
                    if (gain[i] < 0) gain[i] = 0; // 수치 하한
                }
            }
        }
        return order;
    }

    /** 인터페이스형 cosine */
    public static <T extends HasVectorAndScore<T>> double cosine(T a, T b) {
        return cosine(a.getVector(), b.getVector());
    }

    /** 벡터 cosine */
    public static double cosine(float[] x, float[] y) {
        if (x == null || y == null) return 0.0;
        int m = Math.min(x.length, y.length);
        if (m == 0) return 0.0;
        double dot = 0, nx = 0, ny = 0;
        for (int i = 0; i < m; i++) {
            dot += (double) x[i] * y[i];
            nx  += (double) x[i] * x[i];
            ny  += (double) y[i] * y[i];
        }
        if (nx == 0 || ny == 0) return 0.0;
        return dot / (Math.sqrt(nx) * Math.sqrt(ny));
    }
}
