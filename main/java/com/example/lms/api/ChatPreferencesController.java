package com.example.lms.api;

import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.example.lms.web.OwnerKeyBootstrapFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/settings/preferences")
public class ChatPreferencesController {
    private final ChatPreferenceService service;
    private final SettingsService settings;
    private final ChatDefaultsProperties defaults;
    private final ClientOwnerKeyResolver owner;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.llm.DynamicChatModelFactory modelFactory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatModelCatalogService modelCatalog;
    public ChatPreferencesController(ChatPreferenceService service, SettingsService settings,
            ChatDefaultsProperties defaults, ClientOwnerKeyResolver owner) {
        this.service = service; this.settings = settings; this.defaults = defaults; this.owner = owner;
    }
    private String owner() {
        String cookie = OwnerKeyBootstrapFilter.usableOwnerKey(owner.ownerKey());
        if (cookie == null) throw new IllegalArgumentException("missing_owner");
        return AttachmentOwnerIdentity.forAnonymous(cookie).hash();
    }
    @GetMapping public ResponseEntity<?> get() {
        String scope = owner();
        return response(service.read(scope), scope);
    }
    @PatchMapping public ResponseEntity<?> patch(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        checkOrigin(request);
        if (body.keySet().stream().anyMatch(key -> !Set.of("set", "unset", "expectedRevision", "expectedHash", "expectedOwnerScopeId", "owner", "ownerKey").contains(key)))
            throw new IllegalArgumentException("unsupported_patch_field");
        if (!(body.get("expectedRevision") instanceof Number revision)
                || revision.longValue() < 0 || revision.doubleValue() != revision.longValue())
            throw new IllegalArgumentException("expected_revision_required");
        Object hash = body.get("expectedHash");
        if (hash != null && !(hash instanceof String text && text.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("invalid_expected_hash");
        if (body.get("set") != null && !(body.get("set") instanceof Map)) throw new IllegalArgumentException("invalid_set");
        if (body.get("unset") != null && (!(body.get("unset") instanceof List<?> keys)
                || keys.stream().anyMatch(key -> !(key instanceof String)))) throw new IllegalArgumentException("invalid_unset");
        @SuppressWarnings("unchecked") Map<String, Object> set = (Map<String, Object>) body.getOrDefault("set", Map.of());
        @SuppressWarnings("unchecked") List<String> unset = (List<String>) body.getOrDefault("unset", List.of());
        String scope = owner(); // Body, query and public owner headers never select the persistence target.
        if (set.containsKey("chatTraceEnabled") || unset.contains("chatTraceEnabled")) {
            Object expectedScope = body.get("expectedOwnerScopeId");
            if (!(expectedScope instanceof String text && text.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("expected_owner_scope_required");
            // Equality only: this value cannot select another owner's persistence target.
            if (!scope.equals(expectedScope)) throw new ChatPreferenceService.Conflict();
        }
        var saved = service.patch(scope, set, unset, revision.longValue(), (String) hash);
        var observed = service.read(scope);
        if (!saved.equals(observed)) throw new ChatPreferenceService.Conflict();
        return response(observed, scope);
    }
    private ResponseEntity<?> response(ChatPreferenceService.State state, String scope) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String actor = auth == null || !auth.isAuthenticated() ? null : auth.getName();
        var factory = modelCatalog == null ? defaults.values()
                : modelCatalog.firstSessionDefaults(defaults.values(), AttachmentOwnerIdentity.forActor(actor, owner.ownerKey()).hash());
        var admin = settings.getChatAdminOverrides();
        var request = ChatRequestDto.builder().build();
        request.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(state.overrides(), admin, Map.of(), factory));
        var resolved = ChatRequestSettingsMerger.resolve(request, state.overrides(),
                admin, defaults, LoggerFactory.getLogger(getClass()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overrides", state.overrides()); result.put("effective", resolved.effective());
        result.put("factoryDefaults", factory); result.put("sources", resolved.sources());
        result.put("revision", state.revision()); result.put("hash", state.hash());
        result.put("ownerScope", "cookie"); result.put("defaultsVersion", defaults.getDefaultsVersion());
        // Correlates a browser draft with this server-derived scope; never accepted as authority.
        result.put("ownerScopeId", scope);
        String model = (String) resolved.effective().get("model");
        String selection = (String) resolved.effective().get("modelSelectionMode");
        var capability = modelFactory == null
                ? new com.example.lms.llm.DynamicChatModelFactory.TemperatureCapability(
                        com.example.lms.llm.ModelCapabilities.Support.UNKNOWN, "route_unobserved")
                : modelFactory.temperatureCapability(model);
        if ("auto".equals(selection)) capability = new com.example.lms.llm.DynamicChatModelFactory.TemperatureCapability(
                com.example.lms.llm.ModelCapabilities.Support.UNKNOWN, "automatic_route_unobserved");
        result.put("sampling", Map.of("temperature", Map.of("model", model, "modelSelectionMode", selection,
                "support", capability.support().name(), "reasonCode", capability.reasonCode())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private static void checkOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) throw new SecurityException("cross_origin");
        if (origin == null) return;
        try {
            var uri = URI.create(origin);
            int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            if (!Objects.equals(uri.getScheme(), request.getScheme())
                    || !Objects.equals(uri.getHost(), request.getServerName()) || port != request.getServerPort())
                throw new SecurityException("cross_origin");
        } catch (IllegalArgumentException invalid) { throw new SecurityException("cross_origin"); }
    }
    @ExceptionHandler(ChatPreferenceService.Conflict.class) public ResponseEntity<?> conflict() { return error(409, "revision_conflict"); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid() { return error(400, "invalid_preferences"); }
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> forbidden() { return error(403, "cross_origin"); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unavailable() { return error(503, "preferences_unavailable"); }
    private static ResponseEntity<?> error(int status, String reason) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("reasonCode", reason));
    }
}
