// 경로: src/main/java/com/example/lms/controller/PageController.java
package com.example.lms.web;

import com.example.lms.entity.CurrentModel;
import com.example.lms.entity.ModelEntity;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.service.ModelSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

/**
 * PageController – 전역/관리자 페이지 + 모델 관리 통합 (2025-06-29 최종본)
 * -------------------------------------------------------------------------
 * • 홈, 대시보드, 챗 UI, 모델 설정, 관리자 페이지까지 모두 담당하는 단일 컨트롤러.
 * • 중복되던 모델 데이터 준비 로직을 'prepareModelData' 헬퍼 메서드로 통합하여 재사용.
 * • 모델 저장은 ModelSettingsService로 위임하여 비즈니스 로직을 명확히 분리.
 * • DB에 current_model이 없을 때 application.properties의 기본 모델로 안전하게 폴백.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PageController {

    private final ModelEntityRepository  modelRepo;
    private final CurrentModelRepository currentRepo;
    private final ModelSettingsService   modelSettingsService;

    //@Value("${openai.api.model:gpt-3.5-turbo}")
    private String defaultModel;

    /* ========================= 공통 로직 (Private Helper) ========================= */

    /**
     * ✨ [개선] 여러 핸들러에서 중복되는 모델 데이터 준비 로직을 하나로 통합합니다.
     * @param model 뷰에 데이터를 전달할 Model 객체
     */
    private void prepareModelData(Model model) {
        // 1. 모델 목록을 DB에서 가져와 이름순으로 정렬
        List<ModelEntity> allModels = modelRepo.findAll();
        allModels.sort(Comparator.comparing(ModelEntity::getModelId));

        // 2. 현재 기본 모델 ID를 안전하게 조회 (DB 우선, 없으면 properties 값)
        String currentModelId = currentRepo.findById(1L)
                .map(CurrentModel::getModelId)
                .orElse(defaultModel);

        // 3. 뷰에 데이터 추가
        model.addAttribute("models", allModels);
        model.addAttribute("currentModel", currentModelId);
        log.info("페이지 로드. 현재 적용 모델: {}", currentModelId);
    }

    /* ========================= 기본/대시보드 페이지 ========================= */

    @GetMapping("/")
    public String home() {
        return "redirect:/chat";
    }

    @GetMapping("/index")
    public String dashboard(Model model, Authentication auth) {
        if (auth != null && auth.isAuthenticated()) {
            model.addAttribute("username", auth.getName());
        }
        return "index";
    }

    /* ========================= 핵심: 채팅 UI 페이지 ========================= */

    @GetMapping("/chat")
    public String chatUi(Model model, Authentication auth) {
        prepareModelData(model); // ✨ 공통 메서드로 모델 데이터 준비
        if (auth != null && auth.isAuthenticated()) {
            model.addAttribute("username", auth.getName());
        }
        return "chat-ui";
    }

    /* ========================= 사용자용 모델 설정 페이지 ========================= */

    @GetMapping("/model-settings")
    public String showModelSettings(Model model) {
        prepareModelData(model); // ✨ 공통 메서드 사용
        return "model-settings";
    }

    @PostMapping("/model-settings/save")
    public String saveModelSettings(@RequestParam("defaultModel") String modelId, RedirectAttributes redirectAttributes) {
        try {
            modelSettingsService.changeCurrentModel(modelId);
            redirectAttributes.addFlashAttribute("successMessage", "기본 모델이 '" + modelId + "' (으)로 성공적으로 저장되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            log.error("모델 설정 저장 중 오류 발생", e);
            redirectAttributes.addFlashAttribute("errorMessage", "모델 저장 중 예상치 못한 오류가 발생했습니다.");
        }
        return "redirect:/model-settings";
    }

    /* ========================= 관리자 전용 페이지 ========================= */

    @GetMapping("/admin/dashboard")
    public String showAdminDashboard() {
        return "dashboard";
    }

    @GetMapping("/admin/models")
    public String showAdminModels(Model model) {
        prepareModelData(model); // ✨ 공통 메서드 사용
        return "model-list";
    }
}