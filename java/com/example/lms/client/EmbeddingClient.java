// 경로: com/example/lms/client/EmbeddingClient.java
package com.example.lms.client;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import java.util.concurrent.ThreadLocalRandom;



@Profile("test")
@Component
public class EmbeddingClient {
    // 실제 운영 환경에서는 OpenAI의 Embedding API를 호출해야 합니다.
    // 여기서는 1536차원의 랜덤 벡터를 생성하여 시뮬레이션합니다.
    private static final int EMBEDDING_DIMENSION = 1536;

    public float[] toVector(String text) {
        // 데모용 랜덤 벡터 생성
        float[] vector = new float[EMBEDDING_DIMENSION];
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            vector[i] = ThreadLocalRandom.current().nextFloat();
        }
        return vector;
    }
}