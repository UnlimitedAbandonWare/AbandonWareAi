package com.abandonware.ai.agent.service.ml;

import java.util.Arrays;

/**
 * Minimal ZCA whitening for small batches.
 * Not optimized; meant as a pre-processing utility before similarity/DPP kernels.
 */
public final class ZcaWhitening {

    public static class Model {
        public final double[] mean; // length d
        public final double[][] transform; // d x d (U * (Lambda+epsI)^(-1/2) * U^T)
        public final double eps;
        public Model(double[] mean, double[][] transform, double eps) {
            this.mean = mean; this.transform = transform; this.eps = eps;
        }
    }

    public static double[][] fitTransform(double[][] X, double eps){
        Model m = fit(X, eps);
        return transform(m, X);
    }

    public static Model fit(double[][] X, double eps){
        final int n = X.length;
        final int d = X[0].length;
        double[] mu = new double[d];
        for (double[] row: X){
            for (int j=0;j<d;j++) mu[j]+=row[j];
        }
        for (int j=0;j<d;j++) mu[j]/=n;

        double[][] C = new double[d][d];
        for (double[] row: X){
            for (int i=0;i<d;i++){
                double xi = row[i]-mu[i];
                for (int j=0;j<d;j++){
                    C[i][j]+= xi*(row[j]-mu[j]);
                }
            }
        }
        for (int i=0;i<i;i++){}
        for (int i=0;i<d;i++) for (int j=0;j<d;j++) C[i][j]/=n;

        // Eigen-decomposition via naive power iteration (small d expected)
        // For robustness here we fallback to identity if fails.
        double[][] U = identity(d);
        double[] L = new double[d];
        try{
            double[][] A = copy(C);
            for(int k=0;k<d;k++){
                double[] v = new double[d];
                v[k]=1.0;
                for(int it=0;it<30;it++){
                    double[] Av = matVec(A,v);
                    double norm = Math.sqrt(dot(Av,Av))+1e-12;
                    for(int i=0;i<d;i++) v[i]=Av[i]/norm;
                }
                double[] Av = matVec(A,v);
                double lambda = dot(v,Av);
                L[k]=Math.max(lambda, 0.0);
                for(int i=0;i<d;i++) U[i][k]=v[i];
            }
        }catch(Throwable t){
            U = identity(d);
            Arrays.fill(L, 1.0);
        }
        double[][] S = new double[d][d];
        for(int i=0;i<d;i++){
            double val = 1.0/Math.sqrt(L[i]+eps);
            S[i][i]=val;
        }
        // transform = U * S * U^T
        double[][] US = mul(U,S);
        double[][] T = mul(US, transpose(U));
        return new Model(mu, T, eps);
    }

    public static double[][] transform(Model m, double[][] X){
        int n=X.length, d=X[0].length;
        double[][] Y = new double[n][d];
        for (int i=0;i<n;i++){
            double[] xc = new double[d];
            for (int j=0;j<d;j++) xc[j]=X[i][j]-m.mean[j];
            double[] z = matVec(m.transform, xc);
            Y[i]=z;
        }
        return Y;
    }

    private static double[][] identity(int d){
        double[][] I = new double[d][d];
        for (int i=0;i<d;i++) I[i][i]=1.0;
        return I;
    }
    private static double[][] transpose(double[][] A){
        int n=A.length, m=A[0].length;
        double[][] B = new double[m][n];
        for(int i=0;i<n;i++) for (int j=0;j<m;j++) B[j][i]=A[i][j];
        return B;
    }
    private static double[][] mul(double[][] A, double[][] B){
        int n=A.length, m=A[0].length, p=B[0].length;
        double[][] C = new double[n][p];
        for(int i=0;i<n;i++) for(int k=0;k<m;k++){
            double aik=A[i][k];
            if (aik==0) continue;
            for(int j=0;j<p;j++) C[i][j]+=aik*B[k][j];
        }
        return C;
    }
    private static double[] matVec(double[][] A, double[] x){
        int n=A.length, m=A[0].length;
        double[] y = new double[n];
        for(int i=0;i<n;i++){
            double s=0; for(int j=0;j<m;j++) s+=A[i][j]*x[j];
            y[i]=s;
        }
        return y;
    }
    private static double dot(double[] a, double[] b){
        double s=0; for(int i=0;i<a.length;i++) s+=a[i]*b[i];
        return s;
    }
    private static double[][] copy(double[][] A){
        int n=A.length, m=A[0].length;
        double[][] B = new double[n][m];
        for(int i=0;i<n;i++) System.arraycopy(A[i],0,B[i],0,m);
        return B;
    }
}