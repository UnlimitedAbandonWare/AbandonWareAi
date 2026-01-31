package com.example.lms.service.rag.filter;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;



/**
 * Classifier for detecting whether a document or snippet is related to an education/academy topic.
 * Positive keywords include 교육, 학원, 아카데미, 커리큘럼 등.  Negative keywords capture
 * common false positives such as 반려동물 관련 용어, 자동차 정비 등.  If any negative
 * keyword is present the document is not considered educational regardless of positive matches.
 */
@Component
public class EducationDocClassifier {
    // Positive patterns: education-related terms (case insensitive)
    private static final Pattern POS = Pattern.compile(
            "(교육|학원|아카데미|커리큘럼|수강|모집|강의|강사|캠퍼스|수업|학과|학습|입시|상담)",
            Pattern.CASE_INSENSITIVE
    );
    // Negative patterns: exclude dog/pet/car related terms and tag noise
    private static final Pattern NEG = Pattern.compile(
            "(강아지|반려동물|유기견|펫택시|화장터|주유구|자동차|차량|정비|태그\\b)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Determine if the provided text is predominantly about education/academy topics.
     *
     * @param text input text to classify
     * @return true if the text contains education keywords and no negative keywords
     */
    public boolean isEducation(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase();
        if (NEG.matcher(t).find()) return false;
        return POS.matcher(t).find();
    }
}