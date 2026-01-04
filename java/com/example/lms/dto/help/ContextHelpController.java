package com.example.lms.api.help;

import com.example.lms.dto.help.ContextHelpRequest;
import com.example.lms.dto.help.ContextHelpResponse;
import com.example.lms.service.help.ContextHelpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;



@RestController
@RequestMapping(path = "/api/v1/help", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ContextHelpController {

        private final ContextHelpService helpService;

        /**
         * 프런트(chat.js)에서 Help 버튼 클릭 시 호출.
         * 요청 바디는 { contextType, contextData{ elementId, /* ... *&#47; } } 형태.
         * 응답은 content/message 필드 둘 다 채워서 호환성 제공.
         */
        @PostMapping(path = "/context", consumes = MediaType.APPLICATION_JSON_VALUE)
        public ContextHelpResponse context(@RequestBody ContextHelpRequest req) {
                String text = helpService.getHelpFor(req);
                return new ContextHelpResponse(text);
        }
}