package com.example.lms.api;

import com.example.lms.service.ChatModelCatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

@RestController
public class ChatModelCatalogController {
    private final ChatModelCatalogService catalog;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.example.lms.web.ClientOwnerKeyResolver ownerKeys;
    private String ownerHash() {
        if (ownerKeys == null) return null;
        String key=ownerKeys.ownerKey();
        if ("system:no-request".equals(key)) return null;
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String actor=auth == null || !auth.isAuthenticated() ? null : auth.getName();
        return com.example.lms.service.AttachmentOwnerIdentity.forActor(actor,key).hash();
    }
    public ChatModelCatalogController(ChatModelCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/api/chat/models")
    public ResponseEntity<List<ChatModelCatalogService.Choice>> models(
            @RequestParam(defaultValue = "false") boolean discover) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.choices(discover,ownerHash()));
    }

    /** Metadata-only state recheck for one server-catalog id. Bounded inside the
     *  service; never pulls, warms or generates a model. */
    @PostMapping("/api/chat/models/recheck")
    public ResponseEntity<ChatModelCatalogService.Choice> recheck(@RequestParam String id) {
        return catalog.recheck(id,ownerHash())
                .map(row -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(row))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .cacheControl(CacheControl.noStore()).build());
    }
}
