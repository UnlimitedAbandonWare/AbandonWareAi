package com.example.lms.config;

import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;



@Configuration
public class QueryTransformerConfig {

    @Bean
    @ConditionalOnMissingBean
    public QueryTransformer queryTransformer(OpenAiChatModel llm) {
        /* 사전 교정이 필요하면 아래 Map.of(/* ... *&#47;) 자리에 커스텀 오타사전을 넣어주세요 */
        return new QueryTransformer(llm, java.util.Map.of(), null);
    }
}