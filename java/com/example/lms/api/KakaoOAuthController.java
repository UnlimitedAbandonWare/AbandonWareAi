package com.example.lms.api;

import com.example.lms.service.KakaoOAuthService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * 카카오 OAuth 2.0 로그인/동의 플로우 컨트롤러
 */
@Controller
@RequestMapping("/kakao/oauth")
@RequiredArgsConstructor
public class KakaoOAuthController {
    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthController.class);

    /** REST API Key 등 설정 (@Value) */
    @Value("${kakao.rest-api-key}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    // talk_message 스코프만 요청
    private static final String SCOPE_TALK = "talk_message";

    /** OAuth & 메시지 전송 서비스 */
    private final KakaoOAuthService oauthService;

    /** STEP 1: 인가 페이지로 리다이렉트 */
    @GetMapping("/authorize")
    public String authorize() {
        String authUrl = UriComponentsBuilder
                .fromHttpUrl("https://kauth.kakao.com/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId.trim())
                .queryParam("redirect_uri", redirectUri.trim())
                .queryParam("scope", SCOPE_TALK)
                .encode()
                .toUriString();

        log.info("[KakaoOAuth] redirect → {}", authUrl);
        return "redirect:" + authUrl;
    }

    /** STEP 2: 콜백 후 토큰 교환 & "나에게 보내기" 메시지 전송 */
    @GetMapping("/callback")
    public String callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDesc,
            HttpSession session,
            RedirectAttributes redirect) {

        if (error != null) {
            log.warn("[KakaoOAuth] OAuth 실패: {} - {}", error, errorDesc);
            redirect.addFlashAttribute("error", "카카오 인증 실패: " + (errorDesc != null ? errorDesc : error));
            return "redirect:/login";
        }

        if (code == null) {
            log.warn("[KakaoOAuth] callback 접근, code 파라미터 없음");
            redirect.addFlashAttribute("error", "잘못된 접근입니다. 다시 시도해 주세요.");
            return "redirect:/login";
        }

        log.info("[KakaoOAuth] callback - code={}", code);

        // 토큰 발급
        String accessToken = oauthService.exchangeCodeForToken(code);

        // "나에게 보내기" 메시지 전송 (Default 템플릿)
        oauthService.sendMemoDefault(
                accessToken,
                "[LMS] 인증 성공! 나에게 보내기 테스트 메시지입니다.",
                "https://your-site.com"
        );

        redirect.addFlashAttribute("result", "메시지를 성공적으로 보냈습니다!");
        return "redirect:/";
    }
}