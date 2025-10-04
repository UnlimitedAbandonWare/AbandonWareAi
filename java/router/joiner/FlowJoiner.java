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
}
