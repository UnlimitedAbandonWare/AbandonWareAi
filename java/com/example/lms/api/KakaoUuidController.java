// src/main/java/com/example/lms/api/KakaoUuidController.java
package com.example.lms.api;

import com.example.lms.service.KakaoFriendService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;




@Controller
@RequestMapping("/kakao")
@RequiredArgsConstructor
public class KakaoUuidController {

    private final KakaoFriendService friendService;

    /** 세션에 저장된 access token 꺼낼 때 쓰는 키 */
    private static final String SESSION_TOKEN = "kakaoAccessToken";

    /**
     * 페이지 크기는 서비스/인터페이스에 하드코딩하지 않고, 호출부(Controller)가 정책으로 주입한다.
     * <p>
     * default=10 은 하위호환을 위한 안전망이며, 운영에서는 application.yml 로 명시 권장.
     */
    @Value("${kakao.friends.page-size:10}")
    private int pageSize;

    /**
     * GET  /kakao/uuid-list?page=1
     * 1) 세션에서 토큰 꺼내기
     * 2) offset 계산
     * 3) 서비스 호출(fetchFriendUuids(accessToken, offset))
     * 4) 뷰에 모델 세팅
     * 5) 토큰 없으면 OAuth 재시작
     */
    @GetMapping("/uuid-list")
    public String showUuids(
            @RequestParam(name = "page", defaultValue = "1") int page,
            HttpSession session,
            @ModelAttribute("result") String result,
            Model model
    ) {
        // 1) 세션에서 토큰 꺼내기
        String accessToken = (String) session.getAttribute(SESSION_TOKEN);
        if (accessToken == null) {
            model.addAttribute("error",
                    "세션이 만료되었거나 로그인 정보가 없습니다. 다시 로그인해 주세요.");
            return "redirect:/kakao/oauth/authorize";
        }

        // 2) offset 계산
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;

        // 3) 핵심 호출: accessToken + offset + limit 으로 친구 UUID 리스트 조회 (한 페이지 분량)
        List<String> uuids = friendService.fetchFriendUuids(accessToken, offset, safePageSize);

        // 4) 뷰에 모델 세팅
        model.addAttribute("uuids",   uuids);
        model.addAttribute("page",    safePage);
        model.addAttribute("hasPrev", safePage > 1);
        model.addAttribute("hasNext", uuids.size() == safePageSize);
        model.addAttribute("result",  result);

        return "kakao/uuid-list";
    }

    /**
     * POST /kakao/uuid-list
     *  - 페이지 이동 폼에서 받은 page 값으로 GET 리다이렉트
     */
    @PostMapping("/uuid-list")
    public String movePage(@RequestParam int page,
                           RedirectAttributes redir) {
        redir.addAttribute("page", page);
        return "redirect:/kakao/uuid-list";
    }
}