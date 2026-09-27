package com.example.lms.safepatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChatRecoverySafePatchContractTest {

    @Test
    void chatUiRoutesUseThymeleafTemplateRendering() throws Exception {
        String pageController = source("main/java/com/example/lms/web/PageController.java");
        String webMvcConfig = source("main/java/com/example/lms/config/WebMvcConfig.java");

        assertTrue(Files.exists(Path.of("main/resources/templates/chat-ui.html")));
        assertTrue(Files.exists(Path.of("main/resources/static/js/chat.js")));
        assertTrue(pageController.contains("return \"redirect:/chat\";"));
        assertFalse(pageController.contains("return \"redirect:/index\";"));
        assertTrue(pageController.contains("return \"chat-ui\";"));
        assertFalse(pageController.contains("return \"forward:/chat-ui.html\";"));
        assertFalse(webMvcConfig.contains("addResourceHandler(\"/chat-ui.html\")"));
        assertFalse(webMvcConfig.contains("addResourceHandler(\"/js/**\", \"/css/**\", \"/images/**\", \"/assets/**\")"));
        assertTrue(webMvcConfig.contains("addResourceHandler(\"/js/**\")"));
        assertTrue(webMvcConfig.contains("addResourceLocations(\"classpath:/static/js/\")"));
        assertTrue(webMvcConfig.contains("addResourceHandler(\"/css/**\")"));
        assertTrue(webMvcConfig.contains("addResourceLocations(\"classpath:/static/css/\")"));
    }

    @Test
    void settingsApiAllowsReadsButKeepsWritesAdminOnly() throws Exception {
        String security = squash(source("main/java/com/example/lms/config/AppSecurityConfig.java"));

        String getSettings = ".requestMatchers(HttpMethod.GET,\"/api/settings\",\"/api/settings/**\").permitAll()";
        String postSettings = ".requestMatchers(HttpMethod.POST,\"/api/settings\",\"/api/settings/**\").hasRole(\"ADMIN\")";
        int getRule = security.indexOf(getSettings);
        int postRule = security.indexOf(postSettings);
        int publicChatApi = security.indexOf("\"/api/chat/**\"");

        assertTrue(getRule > 0);
        assertTrue(postRule > getRule);
        assertTrue(publicChatApi > postRule);
        assertFalse(security.contains("\"/api/settings\",\"/api/settings/**\",\"/model-settings\""));
    }

    @Test
    void settingsSaveForbiddenDoesNotBlockChatUi() throws Exception {
        String chatJs = source("main/resources/static/js/chat.js");
        String saveSettings = between(chatJs, "async function saveSettings()", "function isHttp403");
        String sendMessage = between(chatJs, "async function sendMessage()", "async function streamChat");

        assertTrue(saveSettings.contains("await apiCall(\"/api/settings\""));
        assertTrue(saveSettings.contains("method: \"POST\""));
        assertTrue(saveSettings.contains("if (isHttp403(e))"));
        assertTrue(saveSettings.contains("Settings save requires admin"));
        assertTrue(saveSettings.contains("return;"));
        assertFalse(sendMessage.contains("\"/api/settings\""));
        assertFalse(sendMessage.contains("saveSettings("));
        assertTrue(chatJs.contains("isHttp403"));
        assertTrue(chatJs.contains("Settings save requires admin"));
    }

    @Test
    void chatModelDefaultsResolveThroughCanonicalLlmProperty() throws Exception {
        String controller = source("main/java/com/example/lms/api/ChatApiController.java");
        String merger = source("main/java/com/example/lms/api/ChatRequestSettingsMerger.java");
        String yaml = source("main/resources/application.yml");
        String properties = source("main/resources/application.properties");

        assertTrue(controller.contains("SettingsService.KEY_OPENAI_MODEL"));
        assertFalse(controller.contains("KEY_DEFAULT_MODEL = \"DEFAULT_MODEL\""));
        assertTrue(merger.contains("cfg.getOrDefault(SettingsService.KEY_OPENAI_MODEL, ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL)"));
        assertFalse(merger.contains("KEY_DEFAULT_MODEL = \"DEFAULT_MODEL\""));
        assertTrue(yaml.contains("chat-model: ${LLM_CHAT_MODEL:gemma4:26b}"));
        assertTrue(properties.contains("openai.chat.model.default=${llm.chat-model:gemma4:26b}"));
        assertTrue(properties.contains("app.ai.default-model=${llm.chat-model:gemma4:26b}"));
    }

    @Test
    void naverYamlUsesCanonicalBridgePlaceholders() throws Exception {
        String yaml = source("main/resources/application.yml");

        assertTrue(yaml.contains("keys: \"${NAVER_KEYS:}\""));
        assertTrue(yaml.contains("client-id: \"${NAVER_CLIENT_ID:}\""));
        assertTrue(yaml.contains("client-secret: \"${NAVER_CLIENT_SECRET:}\""));
        assertFalse(yaml.contains("${naver.keys:"));
        assertFalse(yaml.contains("${naver.client-id:"));
        assertFalse(yaml.contains("${naver.client-secret:"));
    }

    @Test
    void frontendDoesNotHardcodeModelOverrideHeader() throws Exception {
        String chatJs = source("main/resources/static/js/chat.js");
        String fetchWrapper = source("main/resources/static/js/fetch-wrapper.js");

        assertFalse(chatJs.contains("X-Model-Override"));
        assertFalse(fetchWrapper.contains("X-Model-Override"));
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String between(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        assertTrue(start >= 0, startNeedle);
        int end = text.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(end > start, endNeedle);
        return text.substring(start, end);
    }
}
