
package com.example.lms.planning;

import java.util.List;



public interface SelfAskPlanner {
    enum BranchType { BQ, ER, RC } // Base Question, Entity-Relation, Resolve/Context
    class SubQ {
        public final BranchType type;
        public final String query;
        public SubQ(BranchType type, String query) {
            this.type = type; this.query = query;
        }
    }
    List<SubQ> branch3(String query);
}