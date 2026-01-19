package router.joiner;

public class FlowJoiner {
    public boolean hasRetrieve;
    public boolean hasConsent;
    public FlowJoiner(boolean hasRetrieve, boolean hasConsent){
        this.hasRetrieve = hasRetrieve; this.hasConsent = hasConsent;
    }
    public String[] sequence() {
        if (hasRetrieve && hasConsent) return new String[]{"plan","retrieve","critic_coverage","synth","send_to_kakao"};
        if (hasRetrieve) return new String[]{"plan","retrieve","critic_coverage","synth","send_outbox"};
        if (hasConsent) return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_to_kakao"};
        return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_outbox"};
    }


// === Added by v12: PRCYK Health gating ===
    public String[] sequence(com.example.lms.agent.health.HealthSignals z,
                             com.example.lms.agent.health.HealthWeights w,
                             double degrade, double fallback){
        com.example.lms.agent.health.HealthScorer scorer = new com.example.lms.agent.health.HealthScorer();
        double S = scorer.score(z, w);
        if (S < fallback){
            // Fallback/outbox path
            return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_outbox"};
        } else if (S < degrade){
            // Degraded path (no external send if consent missing)
            if (hasConsent) return new String[]{"plan","retrieve","critic_coverage","synth","send_outbox"};
            return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_outbox"};
        } else {
            // Healthy path
            if (hasConsent) return new String[]{"plan","retrieve","critic_coverage","synth","send_to_kakao"};
            return new String[]{"plan","retrieve","critic_coverage","synth","send_outbox"};
        }
    }
    // === End of v12 addition ===
}