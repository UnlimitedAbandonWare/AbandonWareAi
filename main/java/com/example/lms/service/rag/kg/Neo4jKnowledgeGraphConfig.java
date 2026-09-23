package com.example.lms.service.rag.kg;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Neo4jKnowledgeGraphProperties.class)
public class Neo4jKnowledgeGraphConfig {

    @Bean
    Neo4jKnowledgeGraphClient neo4jKnowledgeGraphClient(Neo4jKnowledgeGraphProperties properties) {
        return new Neo4jKnowledgeGraphClient(properties);
    }
}
