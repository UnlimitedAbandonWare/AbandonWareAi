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
    public ChatModelCatalogController(ChatModelCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/api/chat/models")
    public ResponseEntity<List<ChatModelCatalogService.Choice>> models(
            @RequestParam(defaultValue = "false") boolean discover) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.choices(discover));
    }

    /** Metadata-only state recheck for one server-catalog id. Bounded inside the
     *  service; never pulls, warms or generates a model. */
    @PostMapping("/api/chat/models/recheck")
    public ResponseEntity<ChatModelCatalogService.Choice> recheck(@RequestParam String id) {
        return catalog.recheck(id)
                .map(row -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(row))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .cacheControl(CacheControl.noStore()).build());
    }
}
