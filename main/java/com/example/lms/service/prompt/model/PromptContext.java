package com.example.lms.service.prompt.model;

import java.util.List;



public record PromptContext(
        List<String> webSnippets,
        List<String> vectorSnippets,
        List<String> memItems
) {}