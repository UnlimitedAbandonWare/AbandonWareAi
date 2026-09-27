package com.example.lms.service.answer;

import org.springframework.stereotype.Service;



@Service
public class LengthVerifierService {

    public boolean isShort(String text, int minWords) {
        if (text == null || text.isBlank() || minWords <= 0) return false;
        String norm = text.replaceAll("[^\\p{IsHangul}\\p{L}\\p{Nd}\\s]", " ").trim();
        int count = norm.isEmpty() ? 0 : norm.split("\\s+").length;
        return count < minWords;
    }
}