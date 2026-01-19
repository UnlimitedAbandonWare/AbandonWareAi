package service.rag.model;

/**
 * Immutable representation of a scored document returned by the retrieval and fusion stages.
 * <p>
 * Each instance encapsulates a document identifier, optional title and snippet, the
 * source from which it was retrieved, a score and an original rank.  The
 * {@link #withScore(double)} method returns a copy with an updated score while
 * preserving the rest of the fields.
 */
public final class ScoredDoc {
    private final String id;
    private final String title;
    private final String snippet;
    private final String source;
    private final double score;
    private final int rank;

    public ScoredDoc(String id, String title, String snippet, String source, double score, int rank) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.source = source;
        this.score = score;
        this.rank = rank;
    }

    private ScoredDoc(ScoredDoc other, double newScore) {
        this.id = other.id;
        this.title = other.title;
        this.snippet = other.snippet;
        this.source = other.source;
        this.rank = other.rank;
        this.score = newScore;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getSource() {
        return source;
    }

    public double getScore() {
        return score;
    }

    public int getRank() {
        return rank;
    }

    /**
     * Return a copy of this document with an updated score.
     *
     * @param newScore new score
     * @return new ScoredDoc instance
     */
    public ScoredDoc withScore(double newScore) {
        return new ScoredDoc(this, newScore);
    }
}