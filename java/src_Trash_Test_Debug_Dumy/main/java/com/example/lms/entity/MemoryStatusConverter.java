package com.example.lms.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MemoryStatusConverter
        implements AttributeConverter<TranslationMemory.MemoryStatus, String> {

    @Override
    public String convertToDatabaseColumn(TranslationMemory.MemoryStatus status) {
        // null 허용
        return status == null ? null : status.name();
    }

    @Override
    public TranslationMemory.MemoryStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            // null → 기본 ACTIVE
            return TranslationMemory.MemoryStatus.ACTIVE;
        }

        String trimmed = dbData.trim();
        // 1) 숫자 ordinal 처리 (레거시 호환)
        if (trimmed.length() == 1 && Character.isDigit(trimmed.charAt(0))) {
            try {
                int ordinal = Integer.parseInt(trimmed);
                TranslationMemory.MemoryStatus[] values = TranslationMemory.MemoryStatus.values();
                if (ordinal >= 0 && ordinal < values.length) {
                    return values[ordinal];
                }
            } catch (NumberFormatException ignored) {
                // 넘어가서 name 기반 lookup
            }
        }

        // 2) 이름 기반 처리
        try {
            return TranslationMemory.MemoryStatus.valueOf(trimmed);
        } catch (IllegalArgumentException ex) {
            // 알 수 없는 값이면 안전하게 ACTIVE 반환
            return TranslationMemory.MemoryStatus.ACTIVE;
        }
    }
}
