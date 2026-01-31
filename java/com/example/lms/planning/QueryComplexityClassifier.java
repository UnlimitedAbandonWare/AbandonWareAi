
package com.example.lms.planning;


public interface QueryComplexityClassifier {
    ComplexityScore score(String query);
}