
package com.example.moe;

import java.util.Arrays;



/**
 * Minimal tensor ops for small matrices (double[][]). Not optimized.
 */
public final class TensorOps {

    private TensorOps() {}

    /** Matrix multiply: A [n x d] * B^T [m x d] -> [n x m] using dot products with B transposed flag. */
    public static double[][] matmulABt(double[][] A, double[][] B) {
        int n = A.length;
        int d = (n == 0) ? 0 : A[0].length;
        int m = B.length;
        if (m == 0) return new double[0][0];
        int d2 = B[0].length;
        if (d != d2) throw new IllegalArgumentException("Inner dims differ: " + d + " vs " + d2);
        double[][] out = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double s = 0.0;
                for (int k = 0; k < d; k++) s += A[i][k] * B[j][k];
                out[i][j] = s;
            }
        }
        return out;
    }

    /** Matrix multiply: A [n x m] * B [m x p] -> [n x p]. */
    public static double[][] matmul(double[][] A, double[][] B) {
        int n = A.length;
        int m = (n == 0) ? 0 : A[0].length;
        int m2 = B.length;
        if (m != m2) throw new IllegalArgumentException("Inner dims differ: " + m + " vs " + m2);
        int p = (m2 == 0) ? 0 : B[0].length;
        double[][] out = new double[n][p];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < p; k++) {
                double s = 0.0;
                for (int j = 0; j < m; j++) s += A[i][j] * B[j][k];
                out[i][k] = s;
            }
        }
        return out;
    }

    /** Row-wise softmax in place. */
    public static double[][] softmaxRows(double[][] X) {
        int n = X.length;
        if (n == 0) return X;
        int m = X[0].length;
        for (int i = 0; i < n; i++) {
            double max = -Double.MAX_VALUE;
            for (int j = 0; j < m; j++) if (X[i][j] > max) max = X[i][j];
            double sum = 0.0;
            for (int j = 0; j < m; j++) {
                X[i][j] = Math.exp(X[i][j] - max);
                sum += X[i][j];
            }
            double inv = 1.0 / (sum + 1e-12);
            for (int j = 0; j < m; j++) X[i][j] *= inv;
        }
        return X;
    }

    /** Apply scale to all elements: X / sqrt(dk). */
    public static void scale(double[][] X, double s) {
        int n = X.length; if (n == 0) return;
        int m = X[0].length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) X[i][j] *= s;
        }
    }

    /** Right multiply each row by vector g: sum_j g_j * row_j (mixture of sources). */
    public static double[] weightedSum(double[][][] perSource, double[] g, int row) {
        int J = perSource.length;
        int d = (J == 0) ? 0 : perSource[0][row].length;
        double[] out = new double[d];
        for (int j = 0; j < J; j++) {
            double w = g[j];
            double[] arr = perSource[j][row];
            for (int k = 0; k < d; k++) out[k] += w * arr[k];
        }
        return out;
    }

    /** RMSNorm over last dimension. */
    public static double[] rmsnorm(double[] x, double eps) {
        double sumsq = 0.0;
        for (double v : x) sumsq += v * v;
        double rms = Math.sqrt(sumsq / (x.length + 1e-12) + eps);
        double inv = 1.0 / rms;
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] * inv;
        return y;
    }

    /** GELU (approx). */
    public static double gelu(double x) {
        return 0.5 * x * (1.0 + Math.tanh(Math.sqrt(2.0 / Math.PI) * (x + 0.044715 * Math.pow(x, 3))));
    }

    /** Simple FFN (two-layer MLP) - optional; can be identity. */
    public static double[] ffn(double[] x, double[][] W1, double[] b1, double[][] W2, double[] b2) {
        int h = W1[0].length;
        int d = W2[0].length;
        // x: [d], W1: [d x h], W2: [h x d]
        double[] h1 = new double[h];
        for (int j = 0; j < h; j++) {
            double s = b1[j];
            for (int i = 0; i < x.length; i++) s += x[i] * W1[i][j];
            h1[j] = gelu(s);
        }
        double[] y = new double[d];
        for (int k = 0; k < d; k++) {
            double s = b2[k];
            for (int j = 0; j < h; j++) s += h1[j] * W2[j][k];
            y[k] = s;
        }
        return y;
    }

    /** Softmax over vector (1D). */
    public static double[] softmax(double[] z, double tau) {
        double max = -Double.MAX_VALUE;
        for (double v : z) if (v/tau > max) max = v/tau;
        double sum = 0.0;
        double[] y = new double[z.length];
        for (int i = 0; i < z.length; i++) {
            y[i] = Math.exp(z[i]/tau - max);
            sum += y[i];
        }
        double inv = 1.0 / (sum + 1e-12);
        for (int i = 0; i < z.length; i++) y[i] *= inv;
        return y;
    }

    public static int[] argsortDesc(double[] arr) {
        Integer[] idx = new Integer[arr.length];
        for (int i = 0; i < arr.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (i, j) -> Double.compare(arr[j], arr[i]));
        int[] out = new int[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = idx[i];
        return out;
    }

    /** L2 norm squared of difference between vectors. */
    public static double l2sq(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return s;
    }
}