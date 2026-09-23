package service.rag.planner;

import java.util.Optional;

public class SelfAskLightDemo {
    private static final System.Logger LOG = System.getLogger(SelfAskLightDemo.class.getName());

    public static void main(String[] args) {
        SynonymDictionary syn = new SynonymDictionary();
        SelfAskPlanner planner = new SelfAskPlanner(syn, Optional.empty(), Optional.empty(), Optional.empty());
        SelfAskResult result = planner.plan("demo", 3, 5);
        LOG.log(System.Logger.Level.INFO, "selfask.demo originalLength={0} subQueryCount={1} fusedTopKSize={2}",
                result.original == null ? 0 : result.original.length(),
                result.subQueries == null ? 0 : result.subQueries.size(),
                result.fusedTopK == null ? 0 : result.fusedTopK.size());
    }
}
