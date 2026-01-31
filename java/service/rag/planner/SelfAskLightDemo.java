package service.rag.planner;

import java.util.*;

public class SelfAskLightDemo {
    public static void main(String[] args) {
        SynonymDictionary syn = new SynonymDictionary();
        SelfAskPlanner p = new SelfAskPlanner(syn, Optional.empty(), Optional.empty(), Optional.empty());
        SelfAskResult r = p.plan("시스템 성과 지표", 3, 5);
        System.out.println("Original: " + r.original);
        for (SelfAskResult.SubQuery sq : r.subQueries) {
            System.out.println("- [" + sq.kind + "] " + sq.text);
        }
        System.out.println("FusedTopK size=" + (r.fusedTopK == null ? 0 : r.fusedTopK.size()));
    }
}