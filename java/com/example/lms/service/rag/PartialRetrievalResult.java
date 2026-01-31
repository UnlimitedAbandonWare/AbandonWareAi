package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import java.util.ArrayList;
import java.util.List;



/**
 * A container for partial retrieval results emitted by each handler in the
 * resilient retrieval chain.  Each handler may choose to set the
 * {@link #handled} flag to indicate that it has fully satisfied the
 * retrieval request and no further handlers should run.  The handler
 * accumulates a list of {@link Content} segments, evidence identifiers and
 * explanatory notes.  The {@link #append(PartialRetrievalResult)} method
 * merges another result into this one.
 */
public class PartialRetrievalResult {
    /**
     * Whether this handler has fulfilled the query.  When true subsequent
     * handlers in the chain should not execute.  Handlers are advised to
     * always return false to allow downstream stages to supplement the
     * result unless they are certain that further retrieval would not
     * contribute meaningfully.
     */
    private boolean handled;
    /** The list of content segments produced by this handler. */
    private final List<Content> contents;
    /** Evidence identifiers such as URLs or document ids. */
    private final List<String> evidences;
    /** Diagnostic notes describing how the handler produced its result. */
    private final List<String> notes;

    /**
     * Default constructor creating an unhandled result with empty lists.
     */
    public PartialRetrievalResult() {
        this(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Full constructor.  Copies the provided lists; callers should not
     * mutate the input lists after construction.
     *
     * @param handled   whether the handler has satisfied the request
     * @param contents  list of content segments (may be null)
     * @param evidences list of evidence ids (may be null)
     * @param notes     list of diagnostic notes (may be null)
     */
    public PartialRetrievalResult(boolean handled, List<Content> contents,
                                  List<String> evidences, List<String> notes) {
        this.handled = handled;
        this.contents = (contents != null) ? new ArrayList<>(contents) : new ArrayList<>();
        this.evidences = (evidences != null) ? new ArrayList<>(evidences) : new ArrayList<>();
        this.notes = (notes != null) ? new ArrayList<>(notes) : new ArrayList<>();
    }

    /**
     * Return a new PartialRetrievalResult flagged as not handled and with
     * empty lists.  Intended to be used by handlers when they choose to
     * delegate retrieval to downstream stages.
     *
     * @return a fresh unhandled result
     */
    public static PartialRetrievalResult empty() {
        return new PartialRetrievalResult(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Merge another partial result into this one.  The handled flag is
     * combined using logical OR; lists are concatenated in order.  This
     * method mutates the current instance.
     *
     * @param other the other result to merge (null is treated as empty)
     */
    public void append(PartialRetrievalResult other) {
        if (other == null) {
            return;
        }
        this.handled = this.handled || other.handled;
        if (other.contents != null && !other.contents.isEmpty()) {
            this.contents.addAll(other.contents);
        }
        if (other.evidences != null && !other.evidences.isEmpty()) {
            this.evidences.addAll(other.evidences);
        }
        if (other.notes != null && !other.notes.isEmpty()) {
            this.notes.addAll(other.notes);
        }
    }

    public boolean isHandled() {
        return handled;
    }

    public void setHandled(boolean handled) {
        this.handled = handled;
    }

    public List<Content> getContents() {
        return contents;
    }

    public List<String> getEvidences() {
        return evidences;
    }

    public List<String> getNotes() {
        return notes;
    }
}