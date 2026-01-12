// src/main/java/com/example/lms/config/PineconeProps.java
package com.example.lms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;



@Getter
@Setter
@ConfigurationProperties(prefix = "pinecone")
public class PineconeProps {

    /** maps to pinecone.api.key */
    private Api api = new Api();

    /** maps to pinecone.environment */
    private String environment;

    /** maps to pinecone.project.id */
    private Project project = new Project();

    /** maps to pinecone.index.name */
    private Index index = new Index();

    /** maps to pinecone.namespace (없으면 기본값 "") */
    private String namespace = "";

    @Getter @Setter
    public static class Api {
        /** pinecone.api.key */
        private String key;
    }

    @Getter @Setter
    public static class Project {
        /** pinecone.project.id */
        private String id;
    }

    @Getter @Setter
    public static class Index {
        /** pinecone.index.name */
        private String name;
    }

    /* ==== 기존 코드 호환용 편의 getter ==== */
    public String getApiKey()    { return api != null ? api.getKey() : null; }
    public String getProjectId() { return project != null ? project.getId() : null; }
    public String getIndex()     { return index != null ? index.getName() : null; }
}