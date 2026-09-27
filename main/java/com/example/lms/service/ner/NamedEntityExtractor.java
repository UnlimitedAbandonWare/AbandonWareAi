package com.example.lms.service.ner;

import java.util.List;

public interface NamedEntityExtractor {
    List<String> extract(String text);
}
