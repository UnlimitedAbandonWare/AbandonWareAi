package com.example.lms.search.terms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;



/**
 * Data transfer object representing keyword selections derived from an LLM.
 *
 * <p>The selected terms are divided into several buckets: exact phrases
 * to be quoted verbatim in search, must-have keywords that define the core
 * topic, optional should keywords that may improve recall, a list of
 * negative terms to exclude, preferred domains to boost or restrict
 * results and known aliases.  Each field is modelled as a list to allow
 * multiple values.  Lombok annotations generate the boilerplate getters,
 * setters and constructors.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelectedTerms {
    /**
     * Exact phrases to wrap in quotation marks when constructing search
     * queries.  These typically correspond to proper nouns such as
     * company names, titles or specialised terminology.
     */
    private List<String> exact;

    /**
     * Must-have keywords representing the essential concepts in the
     * conversation.  The planner will prioritise these terms when
     * assembling queries.
     */
    private List<String> must;

    /**
     * Secondary keywords that may improve recall but are not strictly
     * required to retrieve relevant results.  They complement the
     * must-have terms.
     */
    private List<String> should;

    /**
     * Additional keywords that may or may not be helpful.  This field is
     * reserved for future use and is currently unused by the planner.
     */
    private List<String> maybe;

    /**
     * Words or phrases to exclude from search results.  The planner or
     * downstream handlers may use these to apply negative filters or
     * subtraction operators when constructing queries.
     */
    private List<String> negative;

    /**
     * Preferred domains for searching.  These values may be used to boost
     * or restrict results to trusted websites.
     */
    private List<String> domains;

    /**
     * Alternate spellings or romanisations of key entities.  These can be
     * surfaced to the user for interactive refinement or applied as
     * additional search tokens.
     */
    private List<String> aliases;

    /**
     * Inferred domain profile (e.g., "tech", "general", "official").
     * Populated by the keyword selection LLM when it wants to hint
     * which domain whitelist or ranking profile is appropriate.
     * Optional: when null, callers should fall back to their default
     * profile (e.g., "general").
     */
    private String domainProfile;

}