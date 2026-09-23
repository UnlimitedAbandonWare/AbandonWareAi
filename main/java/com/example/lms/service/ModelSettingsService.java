package com.example.lms.service;

import com.example.lms.entity.CurrentModel;
import com.example.lms.llm.ConfiguredLocalChatModels;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OpenAiModelSelectionPolicy;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ModelSettingsService {
    private static final Logger log = LoggerFactory.getLogger(ModelSettingsService.class);

    private final ModelEntityRepository modelRepo;
    private final CurrentModelRepository currentRepo;

    @Value("${llm.openai.endpoint-compat.save-guard.mode:${nova.llm.endpoint-compat.save-guard.mode:BLOCK}}")
    private String endpointCompatSaveGuardMode;

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

    @Transactional
    public void changeCurrentModel(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new IllegalArgumentException("Model ID is required.");
        }

        String trimmedModelId = modelId.trim();
        String lower = trimmedModelId.toLowerCase(Locale.ROOT);
        String modelHash = SafeRedactor.hashValue(trimmedModelId);
        int modelLength = trimmedModelId.length();

        boolean effectiveAllowRemote = effectiveAllowRemoteModelSelection();

        log.info("[AWX2AF2][model-picker:save] modelHash={} modelLength={} provider={} allowRemote={}",
                modelHash, modelLength, llmProvider, effectiveAllowRemote);

        if (lower.equals("babbage-002") || lower.contains("embedding")) {
            log.warn("[ModelSettings] embedding/legacy model refused modelHash={} modelLength={}",
                    modelHash, modelLength);
            throw new IllegalArgumentException("Embedding or legacy models cannot be used as the default chat model.");
        }
        if (ModelCapabilities.isPlaceholderModelId(trimmedModelId)) {
            log.warn("[ModelSettings] placeholder model refused modelHash={} modelLength={}",
                    modelHash, modelLength);
            throw new IllegalArgumentException("Selected model is not a concrete chat model.");
        }

        if (isLocalProvider() && isRemoteLookingModelId(lower) && !effectiveAllowRemote) {
            log.warn("[AWX2AF2][model-policy] refused remote-looking modelHash={} modelLength={} provider={} allowRemote={} reason=local_provider",
                    modelHash, modelLength, llmProvider, effectiveAllowRemote);
            throw new IllegalArgumentException("Local provider mode blocks remote model selection.");
        }

        if (OpenAiEndpointCompatibility.isLikelyCompletionsOnlyModelId(trimmedModelId)) {
            String msg = "Selected model is not compatible with /v1/chat/completions. "
                    + "Use a chat-compatible model or set llm.openai.endpoint-compat.save-guard.mode=WARN/ALLOW.";
            String mode = endpointCompatSaveGuardMode == null ? "BLOCK" : endpointCompatSaveGuardMode.trim();
            if ("WARN".equalsIgnoreCase(mode)) {
                log.warn("[ModelSettings] endpoint compatibility warning modelHash={} modelLength={} mode=WARN",
                        modelHash, modelLength);
            } else if (!"ALLOW".equalsIgnoreCase(mode)) {
                log.warn("[ModelSettings] endpoint compatibility blocked modelHash={} modelLength={} mode={}",
                        modelHash, modelLength, mode);
                throw new IllegalArgumentException(msg);
            }
        }

        if (!modelRepo.existsById(trimmedModelId)
                && !isConfiguredLocalChatModel(trimmedModelId)
                && !isConfiguredRemoteChatModel(trimmedModelId, effectiveAllowRemote)) {
            log.warn("[ModelSettings] unknown model id change attempt modelHash={} modelLength={}",
                    modelHash, modelLength);
            throw new IllegalArgumentException("Selected model is not registered.");
        }

        Optional<CurrentModel> optionalCurrentModel = currentRepo.findById(1L);
        optionalCurrentModel.ifPresentOrElse(
                currentModel -> {
                    currentModel.setModelId(trimmedModelId);
                    currentRepo.save(currentModel);
                },
                () -> {
                    CurrentModel newCurrentModel = new CurrentModel();
                    newCurrentModel.setId(1L);
                    newCurrentModel.setModelId(trimmedModelId);
                    currentRepo.save(newCurrentModel);
                }
        );

        log.info("[ModelSettings] default model changed modelHash={} modelLength={}", modelHash, modelLength);
    }

    private boolean isLocalProvider() {
        return "local".equalsIgnoreCase(llmProvider == null ? "" : llmProvider.trim());
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

    private boolean isConfiguredLocalChatModel(String candidateModelId) {
        return isLocalProvider()
                && ConfiguredLocalChatModels.contains(localChatModels, candidateModelId);
    }

    private boolean isConfiguredRemoteChatModel(String modelId, boolean effectiveAllowRemote) {
        return effectiveAllowRemote
                && isRemoteLookingModelId(modelId)
                && OpenAiModelSelectionPolicy.isConfiguredOpenAiChatModel(modelId, openAiChatModels);
    }
}
