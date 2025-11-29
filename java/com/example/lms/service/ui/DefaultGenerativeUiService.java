package com.example.lms.service.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;



/**
 * Default implementation of {@link GenerativeUiService} that uses Jsoup to sanitize
 * generated HTML.  The JSON specification is naively converted to a string;
 * customization may be added as needed to render complex structures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultGenerativeUiService implements GenerativeUiService {

    private final ObjectMapper objectMapper;

    @Override
    public String toSafeHtml(String jsonSpec) {
        if (jsonSpec == null || jsonSpec.isBlank()) {
            return "";
        }
        try {
            // Attempt to parse the JSON to ensure it's well-formed.
            JsonNode node = objectMapper.readTree(jsonSpec);
            String rawHtml = node.toString();
            return Jsoup.clean(rawHtml, Safelist.relaxed());
        } catch (Exception e) {
            // Fall back to sanitizing the raw input.
            try {
                return Jsoup.clean(jsonSpec, Safelist.relaxed());
            } catch (Exception ignore) {
                return "";
            }
        }
    }
}