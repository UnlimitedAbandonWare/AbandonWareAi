package com.example.lms.config.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;



@Component
@ConfigurationProperties(prefix = "retrieval.kg")
public class KgStepRegistrarProps {
    /**
     * KG 스텝 삽입 인덱스 (기본 0 = 체인 맨 앞)
     */
    private int orderIndex = 0;

    public int getOrderIndex() {
        return orderIndex;
    }
    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }
}