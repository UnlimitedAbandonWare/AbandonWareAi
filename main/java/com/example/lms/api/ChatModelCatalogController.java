package com.example.lms.api;

import com.example.lms.service.ChatModelCatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
