package com.example.lms.api;

import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ChatTraceContentLists {

    private ChatTraceContentLists() {
    }

    static List<Content> contentList(Object value) {
        if (value instanceof List<?> list) {
            List<Content> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Content content) {
                    out.add(content);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }

    static List<Content> nullableContentList(Object value) {
        if (value == null) {
            return null;
        }
        return contentList(value);
    }
}
