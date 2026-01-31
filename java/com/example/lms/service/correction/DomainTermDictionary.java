package com.example.lms.service.correction;

import java.util.Set;



public interface DomainTermDictionary {
    Set<String> findKnownTerms(String text);
}