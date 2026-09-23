package com.example.lms.service.rag;

public interface QueryComplexityClassifier {
    QueryComplexityGate.Level classify(String query);
}
