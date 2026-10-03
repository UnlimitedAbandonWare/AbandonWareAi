package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import java.util.Map;

public record ResolvedChatSettings(ChatRequestDto request, Map<String, Object> effective, Map<String, String> sources) {}
