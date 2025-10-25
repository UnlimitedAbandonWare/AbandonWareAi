package com.example.lms.search.terms;

import java.util.List;
import java.util.stream.Collectors;
import org.openkoreantext.processor.OpenKoreanTextProcessorJava;
import scala.collection.JavaConverters;




/**
 * Simple fallback extractor for Korean proper nouns.
 *
 * <p>When the LLM based keyword selection fails, this utility extracts
 * proper nouns (NNP) from the original query using OpenKoreanText.
 * The extracted terms are returned as the "must" list.  In addition
 * the entire query, with any parenthesised sections removed and trimmed,
 * is placed into the "exact" list so that it may be quoted verbatim.
 * All other fields of {@link SelectedTerms} are empty.</p>
 */
public final class FallbackKoreanNNP {
    private FallbackKoreanNNP() {}

    public static SelectedTerms extractBasicTerms(String query) {
        if (query == null) {
            query = "";
        }
        // Tokenize and extract proper nouns (NNP)
        var tokens = OpenKoreanTextProcessorJava.tokenize(query);
        var javaList = JavaConverters.seqAsJavaList(tokens);
        List<String> nnp = javaList.stream()
                .filter(t -> t.pos().toString().equals("NNP"))
                .map(t -> t.text())
                .collect(Collectors.toList());
        // Remove any parenthesised sections from the query for the exact phrase
        String exact = query.replaceAll("\\s*\\(.*?\\)\\s*", "").trim();
        return SelectedTerms.builder()
                .must(nnp)
                .exact(List.of(exact))
                .should(List.of())
                .maybe(List.of())
                .negative(List.of())
                .domains(List.of())
                .aliases(List.of())
                .build();
    }
}