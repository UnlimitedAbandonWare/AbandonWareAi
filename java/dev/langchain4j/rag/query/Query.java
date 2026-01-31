package dev.langchain4j.rag.query;


/**
 * Lightweight replacement for the LangChain4j {@code Query} class that was
 * removed in version 1.0.x.  Several components in the LMS codebase still
 * reference the 0.x builder API (removed in later versions), as well as
 * constructors accepting a text and metadata pair.  To preserve backwards
 * compatibility without pulling in the obsolete 0.x artefacts, this class
 * re-implements the minimal surface area required by the application.
 *
 * <p>The class stores a plain text query along with an arbitrary metadata
 * object.  The metadata type is intentionally declared as {@code Object} to
 * remain agnostic of the underlying representation used by upstream
 * libraries.  Consumers should treat the value as opaque and avoid casting
 * unless absolutely necessary.</p>
 */
public class Query {

    /**
     * The raw user query text.  Never {@code null}.
     */
    private final String text;

    /**
     * Arbitrary metadata associated with this query.  May be {@code null}.
     */
    private final Object metadata;

    /**
     * Construct a new {@code Query} with no metadata.  Equivalent to
     * {@code new Query(text, null)}.
     *
     * @param text the natural language query
     */
    public Query(String text) {
        this(text, null);
    }

    /**
     * Construct a new {@code Query} with the given text and metadata.
     *
     * @param text     the natural language query
     * @param metadata an optional metadata object; may be {@code null}
     */
    public Query(String text, Object metadata) {
        this.text = text;
        this.metadata = metadata;
    }

    /**
     * Return the query text.
     *
     * @return the query text, never {@code null}
     */
    public String text() {
        return text;
    }

    /**
     * Return the metadata associated with this query.
     *
     * <p>The metadata may originate from any number of upstream sources and
     * should therefore be treated as an opaque structure.  Callers wishing
     * to extract structured data should inspect the concrete type or use
     * reflection as appropriate.</p>
     *
     * @return the metadata object, or {@code null} if none was set
     */
    public Object metadata() {
        return metadata;
    }

    /**
     * Create a new {@link Builder} for incrementally constructing a query.
     *
     * <p>This mirrors the builder API provided in LangChain4j 0.x.  The
     * resulting {@code Query} instance will carry forward the assigned
     * fields verbatim.  Missing fields will remain {@code null} (for
     * metadata) or default to {@code null} (for text).</p>
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link Query} instances.  Supports chaining.
     */
    public static final class Builder {
        private String text;
        private Object metadata;

        /**
         * Set the query text.
         *
         * @param text the query text
         * @return this builder for chaining
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * Set the metadata for the query.
         *
         * @param metadata the metadata object
         * @return this builder for chaining
         */
        public Builder metadata(Object metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Build a new {@link Query} using the current state of the builder.
         *
         * @return a new {@link Query}
         */
        public Query build() {
            return new Query(this.text, this.metadata);
        }
    }
}