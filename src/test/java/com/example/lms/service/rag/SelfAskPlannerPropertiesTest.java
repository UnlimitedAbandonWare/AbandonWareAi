package com.example.lms.service.rag;

import com.example.lms.config.SelfAskProperties;
import com.example.lms.llm.DynamicChatModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelfAskPlannerPropertiesTest {

    @Test
    void threeLaneDefaultsComeFromSelfAskProperties() {
        SelfAskProperties props = new SelfAskProperties();
        SelfAskProperties.ThreeWay threeWay = new SelfAskProperties.ThreeWay();
        threeWay.setBq(new SelfAskProperties.Lane("custom-bq", "local-a", 1_200L, 0.31d));
        threeWay.setEr(new SelfAskProperties.Lane("custom-er", "local-b", 1_300L, 0.32d));
        threeWay.setRc(new SelfAskProperties.Lane("custom-rc", "api-c", 1_400L, 0.33d));
        props.setThreeWay(threeWay);

        FakeFactory factory = new FakeFactory(Map.of(
                "custom-bq", new StubModel("background query"),
                "custom-er", new StubModel("entity query"),
                "custom-rc", new StubModel("correction query")));
        SelfAskPlanner planner = new SelfAskPlanner(
                new StubModel("legacy query"),
                provider(factory),
                provider(props));

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("source question", 0L);

        assertEquals(3, lanes.size());
        assertEquals("custom-bq", metaFor(lanes, SelfAskPlanner.SubQuestionType.BQ, "model"));
        assertEquals("local-a", metaFor(lanes, SelfAskPlanner.SubQuestionType.BQ, "provider"));
        assertEquals(1260L, metaFor(lanes, SelfAskPlanner.SubQuestionType.BQ, "timeoutMs"));
        assertEquals("custom-er", metaFor(lanes, SelfAskPlanner.SubQuestionType.ER, "model"));
        assertEquals("local-b", metaFor(lanes, SelfAskPlanner.SubQuestionType.ER, "provider"));
        assertEquals("custom-rc", metaFor(lanes, SelfAskPlanner.SubQuestionType.RC, "model"));
        assertEquals("api-c", metaFor(lanes, SelfAskPlanner.SubQuestionType.RC, "provider"));
    }

    private static Object metaFor(List<SelfAskPlanner.SubQuestion> lanes,
            SelfAskPlanner.SubQuestionType lane,
            String key) {
        return lanes.stream()
                .filter(sq -> sq.type == lane)
                .findFirst()
                .orElseThrow()
                .meta
                .get(key);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return Stream.of(value);
            }
        };
    }

    private static final class FakeFactory extends DynamicChatModelFactory {
        private final Map<String, ChatModel> models;

        private FakeFactory(Map<String, ChatModel> models) {
            super(null, null);
            this.models = models;
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP, Integer maxTokens,
                int timeoutSeconds) {
            return models.getOrDefault(modelName, new StubModel(modelName + " query"));
        }
    }

    private record StubModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
