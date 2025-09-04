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

        @PostMapping(path = "/context", consumes = MediaType.APPLICATION_JSON_VALUE)
        public ContextHelpResponse context(@RequestBody ContextHelpRequest req) {
                String text = helpService.getHelpFor(req);
                return new ContextHelpResponse(text);
        }
}
