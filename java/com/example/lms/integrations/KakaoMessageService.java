// src/main/java/com/example/lms/integrations/KakaoMessageService.java
package com.example.lms.integrations;

import com.example.lms.api.KakaoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class KakaoMessageService {

    private final WebClient kakaoWebClient;      // REST API 전용
    private final WebClient kakaoBizWebClient;   // Biz API 전용
    private final KakaoProperties props;

    public KakaoMessageService(
            @Qualifier("kakaoWebClient") WebClient kakaoWebClient,
            @Qualifier("kakaoBizWebClient") WebClient kakaoBizWebClient,
            KakaoProperties props
    ) {
        this.kakaoWebClient    = kakaoWebClient;
        this.kakaoBizWebClient = kakaoBizWebClient;
        this.props             = props;
    }

    /**
     * 친구에게 링크 포함 텍스트 메시지 전송 (REST API)
     */
    public boolean pushUrl(String toUuid, String text, String url) {
        Map<String, Object> link = new HashMap<>();
        link.put("web_url", url);
        link.put("mobile_web_url", url);

        Map<String, Object> template = new HashMap<>();
        template.put("object_type", "text");
        if (text != null)     template.put("text", text);
        template.put("link", link);

        Map<String, Object> body = new HashMap<>();
        body.put("receiver_uuids", List.of(toUuid));
        body.put("template_object", template);

        return kakaoWebClient.post()
                .uri(props.getSendFriendsMessagePath())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + props.getAdminKey())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .then(Mono.just(true))
                .onErrorResume(e -> {
                    log.error("카카오톡 REST API 푸시 실패", e);
                    return Mono.just(false);
                })
                .block();
    }

    /**
     * 나에게 보내기(메모) 전송 (REST API)
     */
    /**
     * 나에게 보내기(메모) 전송 (REST API)
     */
    public boolean pushMemo(String userAccessToken, String text) {
        // 템플릿 객체 생성
        Map<String, Object> template = new HashMap<>();
        template.put("object_type", "text");
        if (text != null) {
            template.put("text", text);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("template_object", template);

        return kakaoWebClient.post()
                .uri(props.getSendMemoPath())                             // 보낼 URI
                .contentType(MediaType.APPLICATION_JSON)                  // JSON 바디
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)  // Bearer 토큰
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .then(Mono.just(true))
                .onErrorResume(e -> {
                    log.error("카카오톡 REST API 메모 전송 실패", e);
                    return Mono.just(false);
                })
                .block();
    }

    /**
     * 알림톡(비즈 API) 템플릿 푸시
     */
    public void pushBizUrl(String userKey, String text, String url) {
        log.debug("알림톡 준비: userKey={}, text={}, url={}", userKey, text, url);

        Map<String, Object> body = new HashMap<>();
        if (props.getBizTemplateId() != null) {
            body.put("template_id", props.getBizTemplateId());
        }
        body.put("receiver_uuids", List.of(userKey));

        Map<String, String> args = new HashMap<>();
        if (text != null) args.put("text", text);
        if (url != null)  args.put("url", url);
        if (!args.isEmpty()) {
            body.put("template_args", args);
        }

        kakaoBizWebClient.post()
                .uri(props.getSendBizPath())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(res -> log.info("알림톡 전송 완료: {}", res))
                .doOnError(e -> log.error("알림톡 전송 실패", e))
                .subscribe();
    }
}
