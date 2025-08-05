// src/main/java/com/example/lms/service/KakaoOAuthServiceImpl.java
package com.example.lms.service;

import com.example.lms.dto.KakaoFriends;
import com.example.lms.service.KakaoFriendService;
import com.example.lms.service.KakaoOAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 카카오 OAuth + "나에게 보내기" 구현체 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoOAuthServiceImpl implements KakaoOAuthService {

    /* ─────────── 설정 (@Value) ─────────── */
    @Value("${kakao.rest-api-key}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    /** ‘restTemplate’ 라는 이름의 빈만 주입 */
    private final @Qualifier("restTemplate") RestTemplate rest;

    /* =====================================================================
     *  1) 인가 URL (컨트롤러에서 직접 쓰면 필요 X)
     * ===================================================================== */
    public String buildAuthorizeUrl() {
        return UriComponentsBuilder
                .fromUriString("https://kauth.kakao.com/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id",    clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope",        "profile_nickname profile_image friends talk_message")
                .toUriString();
    }

    /* =====================================================================
     *  2) 인가 코드 → access_token
     * ===================================================================== */
    @Override
    public String exchangeCodeForToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",   "authorization_code");
        params.add("client_id",    clientId);
        params.add("redirect_uri", redirectUri);
        params.add("code",         code);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        @SuppressWarnings("unchecked")
        var resp = rest.postForObject(
                "https://kauth.kakao.com/oauth/token",
                new HttpEntity<>(params, headers),
                java.util.Map.class
        );
        return resp != null ? (String) resp.get("access_token") : null;
    }

    /* =====================================================================
     *  3) 친구 목록 (검수 완료 앱에서만 필요)
     * ===================================================================== */
    @Override
    public KakaoFriends friends(String accessToken, int offset) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        String url = UriComponentsBuilder
                .fromUriString("https://kapi.kakao.com/v1/api/talk/friends")
                .queryParam("offset", offset)
                .queryParam("limit",  KakaoFriendService.PAGE_SIZE)
                .toUriString();

        ResponseEntity<KakaoFriends> resp = rest.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), KakaoFriends.class);

        return resp.getBody() != null ? resp.getBody() : new KakaoFriends();
    }

    @Override
    public List<String> fetchFriendUuids(String accessToken, int offset) {
        return friends(accessToken, offset)
                .getElements().stream()
                .map(KakaoFriends.Element::getUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /* =====================================================================
     *  4) “나에게 보내기” – Default 템플릿
     * ===================================================================== */
    @Override
    public void sendMemoDefault(String accessToken, String text, String linkUrl) {
        String url = "https://kapi.kakao.com/v2/api/talk/memo/default/send";

        String templateJson = """
            {
              "object_type":"text",
              "text":"%s",
              "link":{"web_url":"%s"},
              "button_title":"바로가기"
            }
            """.formatted(text, linkUrl);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("template_object", templateJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String res = rest.postForObject(url, new HttpEntity<>(params, headers), String.class);
        log.info("[Kakao] sendMemoDefault response → {}", res);
    }
}
