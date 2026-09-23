// ?롪퍔?δ빳? src/main/java/com/example/lms/controller/PageController.java
package com.example.lms.web;

import com.example.lms.entity.CurrentModel;
import com.example.lms.entity.ModelEntity;
import com.example.lms.llm.ConfiguredLocalChatModels;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiModelSelectionPolicy;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.service.ModelSettingsService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * PageController - ?熬곣뫖????㉱?洹먮봿????瑜곷턄嶺뚯솘? + 嶺뚮ㅄ維????㉱?????? (2025-06-29 嶺뚣끉裕뉏펺?⑺돦?
 * -------------------------------------------------------------------------
 * ???? ????類ｊ텠?? 嶺?UI, 嶺뚮ㅄ維?????깆젧, ??㉱?洹먮봿????瑜곷턄嶺뚯솘?濚밸Ŧ?? 嶺뚮ㅄ維筌???????濡ル츎 ??關逾????쳜?猿낆뿉??댁몠.
 * ??繞벿살탮???濡レ맪 嶺뚮ㅄ維????⑥щ턄??繞벿뮻???β돦裕뉐퐲??'prepareModelData' ????嶺뚮∥?꾥땻??類ㅼŦ ??????琉우뿰 ??亦??
 * ??嶺뚮ㅄ維??????? ModelSettingsService???熬곣뫗肉??琉우뿰 ???닷럴???곕츩 ?β돦裕뉐퐲??嶺뚮ㅏ援????釉뚯뫊??
 * ??DB??current_model????怨몃굵 ??application.properties???リ옇???嶺뚮ㅄ維??몄뿉????깆쓧???우벟 ???揶?
 */
@Controller
@RequiredArgsConstructor
public class PageController {
    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    @Value("${demo.interview.enabled:false}")
    private boolean interviewDemo;

    private final ModelEntityRepository  modelRepo;
    private final CurrentModelRepository currentRepo;
    private final ModelSettingsService   modelSettingsService;

    @Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;

	    /**
	     * UI ?リ옇???嶺뚮ㅄ維??嶺??????춯?????ルㅎ臾멩뤆?.
	     * <p>
	     * currentModel?????닷젆???뺥깴?????裕??遊붋??⑤갭? 嶺뚮ㅄ維??몄뿉????곗삓???롪퍔??? ?熬곣뫁夷?筌뤾퍓裕???リ턂鴉??????諭??"嶺????????怨쀬Ŧ
	     * ???揶??紐껊퉵?? 嶺뚮ㅄ維??嶺뚮ㅄ維뽨빳????怨뺤냱?뺢퀡????筌먲퐣議?????babbage-002 ?띠룇?? ???뺥깴??嶺뚮ㅄ維???1?뺢퀡??????????곗꽑
	     * ?リ옇?????ルㅎ臾??濚밸?維?????쒖굣?節낆쾸? ?꾩룇裕뉑틦??紐껊퉵??
	     * </p>
	     */
	    @Value("${app.ai.ui-default-model:${llm.chat-model:gemma4:26b}}")
	    private String uiDefaultModel;

	    /** ?꾩룄??굢???リ옇???嶺뚮ㅄ維????源녿뮡??base). */
	    @Value("${app.ai.default-model:${openai.chat.model.default:gemma4:26b}}")
	    private String backendDefaultModel;

	    @Value("${llm.chat-model:gemma4:26b}")
	    private String localChatModel;

	    @Value("${llm.provider:local}")
	    private String llmProvider;

	    @Value("${app.ai.allow-remote-model-selection:false}")
	    private boolean allowRemoteModelSelection;

	    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
	    private String openAiApiKey;

	    @Value("${app.ai.openai-chat-models:}")
	    private String openAiChatModels;

	    @Value("${app.ai.local-chat-models:${llm.local-chat-models:gemma4:26b,smtek/Qwen3.8-27B:Q3_K_XL,qwen3-vl:8b,qwen3.5:9b,gemma4:12b}}")
	    private String localChatModels;

    /* ========================= ??ㅻ쾹???β돦裕뉐퐲?(Private Helper) ========================= */

	    private boolean isChatSelectable(String modelId) {
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
	        // embedding/legacy 嶺뚮ㅄ維??? 嶺??????遊붋??⑤갭?
	        if (m.contains("embedding") || m.startsWith("text-embedding")) {
	            return false;
	        }
	        if ("babbage-002".equals(m) || "davinci-002".equals(m)) {
	            return false;
	        }
	        if (isRemoteLookingModelId(m)) {
	            return effectiveAllowRemoteModelSelection();
	        }
	        if (!isLocalProvider()) {
	            return true;
	        }
	        return isLocalModelPromotable(m);
	    }

	    private boolean isLocalModelPromotable(String modelId) {
	        if (!ModelCapabilities.isLocalChatModelId(modelId)) {
	            return false;
	        }
	        if (modelRuntimeHealthTracker != null
	                && modelRuntimeHealthTracker.snapshot("local", modelId).isPresent()
	                && !modelRuntimeHealthTracker.isPromotable("local", modelId)) {
	            return false;
	        }
	        if (ConfiguredLocalChatModels.contains(
	                localChatModels, modelId, uiDefaultModel, backendDefaultModel, localChatModel)) {
	            return true;
	        }
	        if (modelRuntimeHealthTracker == null) {
	            return ModelRuntimeHealthTracker.isSeedLocalChatModel(modelId);
	        }
	        return modelRuntimeHealthTracker.isPromotable("local", modelId);
	    }

	    private boolean isLocalProvider() {
	        return "local".equalsIgnoreCase(trimToEmpty(llmProvider));
	    }

	    private static boolean isRemoteLookingModelId(String modelId) {
	        return ModelCapabilities.isRemoteLookingModelId(modelId);
	    }

	    private boolean effectiveAllowRemoteModelSelection() {
	        return OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection(
	                llmProvider,
	                allowRemoteModelSelection,
	                openAiApiKey);
	    }

	    private static String trimToEmpty(String value) {
	        return value == null ? "" : value.trim();
	    }

	    private String configuredBackendDefault() {
	        String backend = trimToEmpty(backendDefaultModel);
	        if (!backend.isBlank() && isChatSelectable(backend)) {
	            return backend;
	        }
	        String local = trimToEmpty(localChatModel);
	        if (!local.isBlank() && isChatSelectable(local)) {
	            return local;
	        }
	        return ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL;
	    }

	    /**
	     * 嶺???븐뻼?????裕?DB 亦껋꼶梨룩땻?????곗삓)?????????????깆쓧???リ옇???嶺뚮ㅄ維?????ルㅎ臾??紐껊퉵??
	     * <ul>
	     *   <li>1??戮곕쭊: app.ai.ui-default-model (local chat default)</li>
	     *   <li>2??戮곕쭊: app.ai.default-model / openai.chat.model.default</li>
	     *   <li>3??戮곕쭊: 嶺뚮ㅄ維???洹먮봾裕??嶺?????/li>
	     * </ul>
	     */
	    private String pickInitialModel(List<ModelEntity> models) {
	        String preferred = (uiDefaultModel != null) ? uiDefaultModel.trim() : "";
	        if (!preferred.isBlank() && isChatSelectable(preferred)) {
	            return preferred;
	        }
	        String backend = configuredBackendDefault();
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
	        return configuredBackendDefault();
	    }

    /**
     * ??[?띠룇裕뉓땻? ?????筌뤾퍓援?????繞벿살탮???濡ル츎 嶺뚮ㅄ維????⑥щ턄??繞벿뮻???β돦裕뉐퐲????濡る룎????????紐껊퉵??
     * @param model ???ｈ굢???⑥щ턄??? ?熬곣뫀堉??Model ?띠룇鍮섊뙼?     */
    private void prepareModelData(Model model) {
	        // 1) 嶺뚮ㅄ維??嶺뚮ㅄ維뽨빳??DB??????띠럾??筌뤾쑴沅?? 嶺??????遊붋??⑤갭???嶺뚮ㅄ維??embedding/legacy)?? ??戮곕뇶
	        List<ModelEntity> allModels = new ArrayList<>(modelRepo.findAll());
	        allModels.removeIf(m -> m == null || !isChatSelectable(m.getModelId()));
	        addConfiguredLocalChatModels(allModels);
	        addConfiguredOpenAiChatModels(allModels);

	        // 2) UI ?リ옇???嶺뚮ㅄ維???嶺뚮ㅄ維뽨빳?????⑸펲嶺?(DB sync 嶺뚯솘????? 嶺뚮ㅄ維뽨빳???異??熬곣뫖六삣슖??怨뺣뼺?
	        String preferred = (uiDefaultModel != null) ? uiDefaultModel.trim() : "";
	        if (!preferred.isBlank() && isChatSelectable(preferred)) {
	            boolean exists = allModels.stream()
	                    .anyMatch(m -> m != null && preferred.equalsIgnoreCase(m.getModelId()));
	            if (!exists) {
	                ModelEntity synthetic = new ModelEntity();
	                synthetic.setModelId(preferred);
	                synthetic.setOwner("ui-default");
	                allModels.add(synthetic);
	            }
	        }

	        // 3) ???藥???筌먲퐣議?
	        allModels.sort(Comparator.comparing(ModelEntity::getModelId, String.CASE_INSENSITIVE_ORDER));

	        // 4) ?熬곣뫗???リ옇???嶺뚮ㅄ維??ID?????깆쓧???우벟 ?브퀗???(DB ??⑥ろ맖, ???섑깴???遊붋??⑤갭???濡?듆 ???깆쓧???リ옇???泥?
	        String currentModelId = currentRepo.findById(1L)
	                .map(CurrentModel::getModelId)
	                .orElse(null);

	        if (currentModelId == null || currentModelId.isBlank() || !isChatSelectable(currentModelId)) {
	            if (currentModelId != null && !currentModelId.isBlank()) {
	                log.warn("[AWX2AF2][model-policy] ignored currentModelHash={} currentModelLength={} provider={} reason=not_local_selectable",
	                        com.example.lms.trace.SafeRedactor.hashValue(currentModelId), currentModelId.length(), llmProvider);
	            }
	            currentModelId = pickInitialModel(allModels);
	        }

	        // 5) ???ｈ굢???⑥щ턄???怨뺣뼺?
	        String defaultModel = configuredBackendDefault();

	        model.addAttribute("models", allModels);
	        model.addAttribute("currentModel", currentModelId);
	        model.addAttribute("defaultModel", defaultModel);
	        model.addAttribute("llmProvider", trimToEmpty(llmProvider));
	        boolean effectiveAllowRemote = effectiveAllowRemoteModelSelection();
	        model.addAttribute("allowRemoteModelSelection", effectiveAllowRemote);
	        log.info("[AWX2AF2][model-picker:init] provider={} allowRemote={} currentHash={} currentLength={} defaultHash={} defaultLength={} count={}",
	                trimToEmpty(llmProvider), effectiveAllowRemote, com.example.lms.trace.SafeRedactor.hashValue(currentModelId), currentModelId == null ? 0 : currentModelId.length(), com.example.lms.trace.SafeRedactor.hashValue(defaultModel), defaultModel == null ? 0 : defaultModel.length(), allModels.size());
    }

    /* ========================= ?リ옇???????類ｊ텠????瑜곷턄嶺뚯솘? ========================= */

	    private void addConfiguredLocalChatModels(List<ModelEntity> allModels) {
	        for (String modelId : ConfiguredLocalChatModels.parse(
	                localChatModels, uiDefaultModel, backendDefaultModel, localChatModel)) {
	            if (!isChatSelectable(modelId)) {
	                continue;
	            }
	            boolean exists = allModels.stream()
	                    .anyMatch(m -> m != null && modelId.equalsIgnoreCase(m.getModelId()));
	            if (exists) {
	                continue;
	            }
	            ModelEntity synthetic = new ModelEntity();
	            synthetic.setModelId(modelId);
	            synthetic.setOwner("local-config");
	            synthetic.setFeatures("chat,local");
	            allModels.add(synthetic);
	        }
	    }

	    private void addConfiguredOpenAiChatModels(List<ModelEntity> allModels) {
	        if (!effectiveAllowRemoteModelSelection()) {
	            return;
	        }
	        for (String modelId : OpenAiModelSelectionPolicy.openAiChatModels(openAiChatModels)) {
	            boolean exists = allModels.stream()
	                    .anyMatch(m -> m != null && modelId.equalsIgnoreCase(m.getModelId()));
	            if (exists || !isChatSelectable(modelId)) {
	                continue;
	            }
	            ModelEntity synthetic = new ModelEntity();
	            synthetic.setModelId(modelId);
	            synthetic.setOwner("openai");
	            synthetic.setFeatures("chat,remote");
	            allModels.add(synthetic);
	        }
	    }

    @GetMapping({"/", "/index.html"})
    public String home() {
        if (interviewDemo) return "forward:/assets/interview/index.html";
        return "redirect:/chat";
    }

    @GetMapping("/index")
    public String dashboard(Model model, Authentication auth) {
        if (interviewDemo) return "forward:/assets/interview/index.html";
        if (auth != null && auth.isAuthenticated()) {
            model.addAttribute("username", auth.getName());
        }
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model,
                        @RequestParam(value = "error", required = false) String error,
                        @RequestParam(value = "logout", required = false) String logout) {
        if (error != null) {
            model.addAttribute("loginStatus", "invalid_credentials");
        } else if (logout != null) {
            model.addAttribute("loginStatus", "signed_out");
        } else {
            model.addAttribute("loginStatus", "sign_in_required");
        }
        return "login";
    }

    /* ========================= ???堉? 嶺????UI ??瑜곷턄嶺뚯솘? ========================= */

    public String chatUi(Model model, Authentication auth) {
        return chatUi(model, auth, null);
    }

    @GetMapping({"/chat", "/chat-ui", "/chat-ui.html"})
    public String chatUi(Model model, Authentication auth,
                         @RequestParam(value = "surface", required = false) String surface) {
        if (interviewDemo) return "forward:/assets/interview/index.html";
        prepareModelData(model);
        if (auth != null && auth.isAuthenticated()) {
            model.addAttribute("username", auth.getName());
        }
        model.addAttribute("chatDiagnosticsEnabled", isAdmin(auth));
        model.addAttribute("chatSurface", normalizeChatSurface(surface));
        return "chat-ui";
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static String normalizeChatSurface(String surface) {
        return "compact".equalsIgnoreCase(trimToEmpty(surface)) ? "compact" : "web";
    }

    /* ========================= ????????嶺뚮ㅄ維?????깆젧 ??瑜곷턄嶺뚯솘? ========================= */

    @GetMapping("/model-settings")
    public String showModelSettings(Model model) {
        prepareModelData(model);
        return "model-settings";
    }

    @PostMapping("/model-settings/save")
    public String saveModelSettings(@RequestParam("defaultModel") String modelId, RedirectAttributes redirectAttributes) {
        try {
            modelSettingsService.changeCurrentModel(modelId);
            redirectAttributes.addFlashAttribute("successMessage", "모델 설정 저장 완료: modelHash="
                    + SafeRedactor.hashValue(modelId) + " modelLength=" + (modelId == null ? 0 : modelId.length()));
        } catch (IllegalArgumentException e) {
            log.debug("Model settings validation rejected modelHash={} modelLength={}",
                    SafeRedactor.hashValue(modelId), modelId == null ? 0 : modelId.length());
            redirectAttributes.addFlashAttribute("errorMessage", "모델 설정 저장 실패: modelHash="
                    + SafeRedactor.hashValue(modelId) + " modelLength=" + (modelId == null ? 0 : modelId.length()));
        } catch (Exception e) {
            log.error("Failed to save model settings type={} errorHash={} errorLength={}",
                    e.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            redirectAttributes.addFlashAttribute("errorMessage", "모델 저장 중 예상치 못한 오류가 발생했습니다.");
        }
        return "redirect:/model-settings";
    }

    /* ========================= ??㉱?洹먮봿???熬곣뫗????瑜곷턄嶺뚯솘? ========================= */

    @GetMapping({"/admin/dashboard", "/admin/rag-ops-cockpit"})
    public String showAdminDashboard() {
        return "dashboard";
    }

    @GetMapping("/admin/models")
    public String showAdminModels(Model model) {
        prepareModelData(model);
        return "model-list";
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }
}
