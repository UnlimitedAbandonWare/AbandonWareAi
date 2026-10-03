package com.example.lms.assist;

import java.util.Objects;
import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;

/** Short-lived logical request state. Capture and bind explicitly across async boundaries. */
public final class JevDecisionScope implements AutoCloseable {
    private static final ThreadLocal<JevDecisionScope> CURRENT=new ThreadLocal<>();
    private final JevDecisionScope previous,owner;
    final String surface;
    final QuestionKey key;
    final DecisionAdmission admission;
    EvaluationHandle handle;
    private ChoiceResult result;
    private boolean closed;
    private JevDecisionScope(String surface,QuestionKey key,DecisionAdmission admission,JevDecisionScope explicitOwner) {
        this.previous=CURRENT.get();
        this.owner=explicitOwner!=null?explicitOwner:previous==null?this:previous.owner;
        this.surface=surface;this.key=Objects.requireNonNull(key);this.admission=Objects.requireNonNull(admission);
        CURRENT.set(this);
    }
    public static JevDecisionScope bind(String surface,QuestionKey key,DecisionAdmission admission) {
        if(!java.util.Set.of("focus","cue","main").contains(surface))throw new IllegalArgumentException("surface");
        return new JevDecisionScope(surface,key,admission,null);
    }
    /** Pass this local object only to trusted request workers, never into wire state or logs. */
    public static JevDecisionScope capture(){var current=CURRENT.get();return current==null?null:current.owner;}
    public static JevDecisionScope bind(JevDecisionScope captured) {
        Objects.requireNonNull(captured);
        if(captured.closed)throw new IllegalStateException("scope closed");
        return new JevDecisionScope(captured.surface,captured.key,captured.admission,captured.owner);
    }
    static JevDecisionScope current(){return capture();}
    synchronized void remember(ChoiceResult observed){result=observed;}
    public synchronized java.util.Optional<ChoiceResult> result(){return java.util.Optional.ofNullable(owner.result);}
    @Override public void close() {
        if(closed)return;
        if(CURRENT.get()!=this)throw new IllegalStateException("scope binding order");
        if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        closed=true;
    }
}
