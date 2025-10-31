
        package com.example.lms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import java.time.Duration;




@Configuration
public class OpenAiConfig {

    // Resolve the API key for OpenAI.  Prefer the `openai.api.key` property
    // and fall back to the OPENAI_API_KEY environment variable.  Do not fall
    // back to other vendor keys (e.g. GROQ_API_KEY) to prevent invalid
    // credentials being used against OpenAI endpoints.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openAiKey;

    @Value("${local-llm.enabled:false}")
    private boolean localEnabled;

    @Value("${local-llm.base-url:}")
    private String localBaseUrl;

    // Base URL for OpenAI public API.  Support overriding via
    // `openai.base-url` or fallback to default.  Note the trailing slash
    // is ensured via ensureTrailingSlash() at use.
    @Value("${openai.base-url:https://api.openai.com/}")
    private String openAiBaseUrl;

    @Bean
    @Primary
    public Object openAiService() {
        // 1) 로컬(OpenAI 호환) 엔드포인트 먼저 처리
        if (localEnabled && localBaseUrl != null && !localBaseUrl.isBlank()) {
            String base = ensureTrailingSlash(localBaseUrl.trim());
            // 로컬 서버는 토큰을 안 볼 수도 있으니, 비었으면 대체 토큰 사용
            String token = (openAiKey == null || openAiKey.isBlank()) ? "ollama" : openAiKey;

            OkHttpClient client = makeClient(token, Duration.ofSeconds(60));
            ObjectMapper mapper = makeMapper();

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(base)
                    .client(client)
                    .addConverterFactory(JacksonConverterFactory.create(mapper))
                    .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                    .build();

            // Retrofit 자체를 빈으로 노출(원한다면 인터페이스 프록시로 교체 가능)
            return retrofit;
        }

        // 2) 퍼블릭(OpenAI) 엔드포인트: 이때는 키 필수
        if (openAiKey == null || openAiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing");
        }
        if (!openAiKey.startsWith("sk-")) {
            throw new IllegalStateException("OpenAI API key must start with 'sk-'");
        }
        // 퍼블릭 OpenAI 엔드포인트일 때도 동일하게 클라이언트/매퍼 구성 가능
        OkHttpClient client = makeClient(openAiKey, Duration.ofSeconds(60));
        ObjectMapper mapper = makeMapper();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(ensureTrailingSlash(openAiBaseUrl))
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create(mapper))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();
        return retrofit;
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /** Configures token header injection and timeout settings without relying on external OpenAI client libraries. */
    private static OkHttpClient makeClient(String token, Duration timeout) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout);
        if (token != null && !token.isBlank()) {
            b.addInterceptor(chain -> {
                Request org = chain.request();
                Request req = org.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .build();
                return chain.proceed(req);
            });
        }
        return b.build();
    }

    /** 필요 시 커스터마이징 */
    private static ObjectMapper makeMapper() {
        return new ObjectMapper();
    }
}