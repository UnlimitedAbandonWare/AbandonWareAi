package com.example.lms.kg;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.kg.Neo4jConfig
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.kg.Neo4jConfig
role: config
flags: [kg]
*/
public class Neo4jConfig {
    @Bean
    public Driver neo4jDriver(@Value("${neo4j.uri:bolt://localhost:7687}") String uri,
                              @Value("${neo4j.user:neo4j}") String user,
                              @Value("${neo4j.password:neo4j}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
}