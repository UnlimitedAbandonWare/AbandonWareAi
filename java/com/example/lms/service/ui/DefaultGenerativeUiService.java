package com.example.lms.service.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Default implementation of {@link GenerativeUiService} that uses Jsoup to sanitize
 * generated HTML.  The JSON specification is naively converted to a string;
 * customization may be added as needed to render complex structures.
 */
@Service
@RequiredArgsConstructor
public class DefaultGenerativeUiService implements GenerativeUiService {
    private static final Logger log = LoggerFactory.getLogger(DefaultGenerativeUiService.class);

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