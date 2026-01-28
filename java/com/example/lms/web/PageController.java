// 경로: src/main/java/com/example/lms/controller/PageController.java
package com.example.lms.web;

import com.example.lms.entity.CurrentModel;
import com.example.lms.entity.ModelEntity;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.service.ModelSettingsService;
import lombok.RequiredArgsConstructor;
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
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * PageController - 전역/관리자 페이지 + 모델 관리 통합 (2025-06-29 최종본)
 * -------------------------------------------------------------------------
 * • 홈, 대시보드, 챗 UI, 모델 설정, 관리자 페이지까지 모두 담당하는 단일 컨트롤러.
 * • 중복되던 모델 데이터 준비 로직을 'prepareModelData' 헬퍼 메서드로 통합하여 재사용.
 * • 모델 저장은 ModelSettingsService로 위임하여 비즈니스 로직을 명확히 분리.
 * • DB에 current_model이 없을 때 application.properties의 기본 모델로 안전하게 폴백.
 */
@Controller
@RequiredArgsConstructor
public class PageController {
    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    private final ModelEntityRepository  modelRepo;
    private final CurrentModelRepository currentRepo;
    private final ModelSettingsService   modelSettingsService;

	    /**
	     * UI 기본 모델(첫 렌더링 시 선택값).
	     * <p>
	     * currentModel이 비어있거나(또는 부적합 모델로 오염된 경우) 프론트는 종종 셀렉트의 "첫 옵션"으로
	     * 폴백합니다. 모델 목록이 알파벳순 정렬이면 babbage-002 같은 레거시 모델이 1번이 될 수 있어
	     * 기본 선택이 깨지는 문제가 발생합니다.
	     * </p>
	     */
	    @Value("${app.ai.ui-default-model:gpt-5.2-chat-latest}")
	    private String uiDefaultModel;

	    /** 백엔드 기본 모델(라우터 base). */
	    @Value("${app.ai.default-model:${openai.chat.model.default:gemma3:27b}}")
	    private String backendDefaultModel;

    /* ========================= 공통 로직 (Private Helper) ========================= */

	    private static boolean isChatSelectable(String modelId) {
	        if (modelId == null) {
	            return false;
	        }
	        String canon = ModelCapabilities.canonicalModelName(modelId);
	        if (canon == null) {
	            return false;
	        }
	        String m = canon.trim().toLowerCase();
	        if (m.isBlank()) {
	            return false;
	        }
	        // embedding/legacy 모델은 채팅에 부적합
	        if (m.contains("embedding") || m.startsWith("text-embedding")) {
	            return false;
	        }
	        if ("babbage-002".equals(m) || "davinci-002".equals(m)) {
	            return false;
	        }
	        return true;
	    }

	    /**
	     * 첫 화면(또는 DB 미설정/오염)에서 사용할 안전한 기본 모델을 선택합니다.
	     * <ul>
	     *   <li>1순위: app.ai.ui-default-model (기본: gpt-5.2-chat-latest)</li>
	     *   <li>2순위: app.ai.default-model / openai.chat.model.default</li>
	     *   <li>3순위: 모델 리스트 첫 항목</li>
	     * </ul>
	     */
	    private String pickInitialModel(List<ModelEntity> models) {
	        String preferred = (uiDefaultModel != null) ? uiDefaultModel.trim() : "";
	        if (!preferred.isBlank() && isChatSelectable(preferred)) {
	            return preferred;
	        }
	        String backend = (backendDefaultModel != null) ? backendDefaultModel.trim() : "";
	        if (!backend.isBlank() && isChatSelectable(backend)) {
	            return backend;
	        }
	        if (models != null && !models.isEmpty()) {
	            for (ModelEntity m : models) {
	                if (m != null && isChatSelectable(m.getModelId())) {
	                    return m.getModelId();
	                }
	            }
	        }
	        return "gpt-5.2-chat-latest";
	    }

    /**
     * ✨ [개선] 여러 핸들러에서 중복되는 모델 데이터 준비 로직을 하나로 통합합니다.
     * @param model 뷰에 데이터를 전달할 Model 객체
     */
    private void prepareModelData(Model model) {
	        // 1) 모델 목록을 DB에서 가져오되, 채팅에 부적합한 모델(embedding/legacy)은 제외
	        List<ModelEntity> allModels = modelRepo.findAll();
	        allModels.removeIf(m -> m == null || !isChatSelectable(m.getModelId()));

	        // 2) UI 기본 모델이 목록에 없다면 (DB sync 지연 등) 목록에만 임시로 추가
	        String preferred = (uiDefaultModel != null) ? uiDefaultModel.trim() : "";
	        if (!preferred.isBlank()) {
	            boolean exists = allModels.stream()
	                    .anyMatch(m -> m != null && preferred.equalsIgnoreCase(m.getModelId()));
	            if (!exists) {
	                ModelEntity synthetic = new ModelEntity();
	                synthetic.setModelId(preferred);
	                synthetic.setOwner("ui-default");
	                allModels.add(synthetic);
	            }
	        }

	        // 3) 이름순 정렬
	        allModels.sort(Comparator.comparing(ModelEntity::getModelId, String.CASE_INSENSITIVE_ORDER));

	        // 4) 현재 기본 모델 ID를 안전하게 조회 (DB 우선, 없거나/부적합하면 안전한 기본값)
	        String currentModelId = currentRepo.findById(1L)
	                .map(CurrentModel::getModelId)
	                .orElse(null);

	        if (currentModelId == null || currentModelId.isBlank() || !isChatSelectable(currentModelId)) {
	            currentModelId = pickInitialModel(allModels);
	        }

	        // 5) 뷰에 데이터 추가
	        model.addAttribute("models", allModels);
	        model.addAttribute("currentModel", currentModelId);
	        model.addAttribute("defaultModel", backendDefaultModel);
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