package app.gate;
public class CitationGate {
    private final int min;
    public CitationGate(int min) { this.min = min; }
    public boolean pass(int citationCount) { return citationCount >= min; }
}
