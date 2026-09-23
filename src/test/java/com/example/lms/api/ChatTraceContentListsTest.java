package com.example.lms.api;

import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTraceContentListsTest {

    @Test
    void contentListDropsNonContentValues() {
        Content first = Content.from("first");
        Content second = Content.from("second");

        List<Content> out = ChatTraceContentLists.contentList(List.of(first, "not-content", second));

        assertEquals(List.of(first, second), out);
    }

    @Test
    void nullableContentListPreservesNullAsDisabledSignal() {
        assertNull(ChatTraceContentLists.nullableContentList(null));
        assertTrue(ChatTraceContentLists.nullableContentList("not-a-list").isEmpty());
        assertTrue(ChatTraceContentLists.contentList("not-a-list").isEmpty());
    }
}
