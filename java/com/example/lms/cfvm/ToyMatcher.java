package com.example.lms.cfvm;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ToyMatcher {
    private ToyMatcher() {}
    static final int MAX_POINTS = 64;
    private static final Map<String, Deque<double[]>> series = new ConcurrentHashMap<>();
    private static final double[] TOY_JB = new double[]{0,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1,0.9,0.8,0.7,0.6,0.5,0.4,0.3,0.2,0.1,0};
    private static final double[] TOY_CB = new double[]{0,0,0,0,0,1,1,1,0,0,0,0,1,1,0,0,0,0,0,0,0};
    public static double updateAndScore(String sid, RawSlot.Stage stage, double jb, double cb) {
        String key = sid + ":" + stage;
        Deque<double[]> dq = series.computeIfAbsent(key, k -> new ArrayDeque<>(MAX_POINTS));
        if (dq.size() == MAX_POINTS) dq.removeFirst();
        dq.addLast(new double[]{jb, cb});
        return similarity(dq);
    }
    private static double similarity(Deque<double[]> dq) {
        if (dq.size() < 8) return 0.0;
        double[][] cur = dq.stream().map(a -> new double[]{a[0], a[1]}).toArray(double[][]::new);
        double s1 = 1.0 - dtw(normalize(extract(cur,0)), normalize(TOY_JB));
        double s2 = 1.0 - dtw(normalize(extract(cur,1)), normalize(TOY_CB));
        return Math.max(0.0, Math.min(1.0, (s1 + s2) / 2.0));
    }
    private static double[] extract(double[][] m, int idx){ double[] out=new double[m.length]; for(int i=0;i<m.length;i++) out[i]=m[i][idx]; return out; }
    private static double[] normalize(double[] v){
        double min=Arrays.stream(v).min().orElse(0), max=Arrays.stream(v).max().orElse(1);
        double d=Math.max(1e-9, max-min); double[] o=v.clone(); for(int i=0;i<o.length;i++) o[i]=(o[i]-min)/d; return o;
    }
    private static double dtw(double[] a, double[] b){
        int n=a.length, m=b.length; double[][] dp=new double[n+1][m+1];
        for (double[] row : dp) Arrays.fill(row, Double.POSITIVE_INFINITY);
        dp[0][0]=0.0;
        for(int i=1;i<=n;i++){ for(int j=1;j<=m;j++){
            double cost=Math.abs(a[i-1]-b[j-1]);
            dp[i][j]=cost+Math.min(dp[i-1][j], Math.min(dp[i][j-1], dp[i-1][j-1]));
        }}
        double maxCost = Math.max(n,m);
        return Math.min(1.0, dp[n][m]/maxCost);
    }
}