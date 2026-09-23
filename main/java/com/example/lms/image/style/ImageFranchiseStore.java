package com.example.lms.image.style;

import java.util.Optional;



/**
 * Simple lookup interface for resolving a style card given a user query and
 * optional memory context.  Implementations may perform lexical, fuzzy or
 * vector based matching.  When no match is found an empty {@link Optional}
 * should be returned rather than throwing an exception.
 */
public interface ImageFranchiseStore {

    /**
     * Attempt to resolve a {@link FranchiseProfile} relevant to the provided
     * query.  The optional memory parameter may contain prior conversation
     * context that can improve matching (e.g. previous franchise names).  A
     * best effort approach should be taken; if the confidence falls below the
     * configured threshold the implementation should return {@link Optional#empty()}.
     *
     * @param query the current user message
     * @param memory recent memory summary or {@code null}
     * @return an optional style card profile
     */
    Optional<FranchiseProfile> resolve(String query, String memory);
}