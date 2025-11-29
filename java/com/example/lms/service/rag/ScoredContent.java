
package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;



/** Content  score 보관용 불변 레코드 */
public record ScoredContent(Content content, double score) {}