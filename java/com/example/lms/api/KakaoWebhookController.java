// src/main/java/com/example/lms/api/KakaoWebhookController.java
package com.example.lms.api;

import com.example.lms.integrations.KakaoMessageService;
import com.example.lms.payload.KakaoWebhookPayload;
import com.example.lms.domain.Assignment;
import com.example.lms.domain.Student;
import com.example.lms.domain.UploadToken;
import com.example.lms.repository.AssignmentRepository;
import com.example.lms.repository.StudentRepository;
import com.example.lms.service.UploadTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 카카오 웹훅 콜백 진입점
 * • intent "ASSIGNMENT_SUBMIT"인 경우에만 처리
 * • 파라미터로 전달된 asgId로 과제 조회 → 30분 유효 업로드 토큰 발급
 * • 학생의 kakaoId로 카톡 푸시 후, Open Builder 형식으로 응답
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/kakao/webhook")
public class KakaoWebhookController {

    private final AssignmentRepository assignmentRepo;
    private final StudentRepository studentRepo;
    private final UploadTokenService tokenSvc;
    private final KakaoMessageService kakaoMsg;

    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(@RequestBody KakaoWebhookPayload payload) {
        // 1) intent 체크
        if (!"ASSIGNMENT_SUBMIT".equals(payload.getIntent())) {
            return ResponseEntity.ok().build();
        }

        // 2) asgId 파라미터로 과제 조회
        Long asgId = Long.valueOf(payload.getParameter("asgId"));
        Assignment asg = assignmentRepo.findById(asgId)
                .orElseThrow(() -> new RuntimeException("과제 없음: id=" + asgId));

        // 3) kakaoId로 학생 조회
        String kakaoId = payload.getUser().getId();
        Student stu = studentRepo.findByKakaoId(kakaoId)
                .orElseThrow(() -> new RuntimeException("학생 미등록: kakaoId=" + kakaoId));

        // 4) 업로드 토큰 발급 (30분 유효)
        UploadToken ut = tokenSvc.issue(asg, stu, Duration.ofMinutes(30));

        // 5) 제출 URL 조합
        String uploadUrl = "https://lms.example.com/upload/" + ut.getToken();

        // 6) 카카오톡 푸시
        kakaoMsg.pushUrl(
                kakaoId,
                "📑 \"" + asg.getTitle() + "\" 제출 링크입니다 (30분 유효)",
                uploadUrl
        );

        // 7) Open Builder 응답 생성
        Map<String, Object> response = Map.of(
                "version", "2.0",
                "template", Map.of(
                        "outputs", List.of(
                                Map.of("simpleText",
                                        Map.of("text",
                                                "제출 링크를 전송했어요! 30분 내 업로드하십시오."
                                        )
                                )
                        )
                )
        );
        return ResponseEntity.ok(response);
    }
}
