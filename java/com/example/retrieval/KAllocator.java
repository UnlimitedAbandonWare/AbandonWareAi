package com.example.retrieval;


public final class KAllocator {
    private KAllocator() {}
    public static int[] allocateK(int K, double[] gate, int[] mins) {
        double sw = K * gate[0];
        double sr = K * gate[1];
        double sm = K * gate[2];

        int kw = Math.max((int)Math.floor(sw), mins[0]);
        int kr = Math.max((int)Math.floor(sr), mins[1]);
        int km = Math.max((int)Math.floor(sm), mins[2]);

        int used = kw + kr + km;
        while (used < K) {
            double fw = sw - Math.floor(sw);
            double fr = sr - Math.floor(sr);
            double fm = sm - Math.floor(sm);
            int argmax = (fw >= fr && fw >= fm) ? 0 : (fr >= fm ? 1 : 2);
            if (argmax==0) kw++; else if (argmax==1) kr++; else km++;
            used++;
        }
        return new int[]{kw, kr, km};
    }
}