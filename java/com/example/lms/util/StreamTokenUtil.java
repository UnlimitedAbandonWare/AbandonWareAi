package com.example.lms.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * SSE/웹스트림 전송을 위한 단순 청크(조각) 분할 유틸리티.
 * HTML이나 텍스트를 고정된 크기로 안전하게 분할합니다.
 */
public final class StreamTokenUtil {

    private StreamTokenUtil() {}

    /**
     * 문자열을 주어진 크기(size)의 조각들로 나눕니다.
     *
     * @param s 원본 문자열 (null이나 빈 문자열도 안전하게 처리)
     * @param size 각 조각의 최대 크기 (1 이상)
     * @return 분할된 문자열 조각들의 리스트 (입력이 비어있으면 빈 리스트 반환)
     */
    public static List<String> chunk(String s, int size) {
        if (s == null || s.isEmpty()) {
            return Collections.emptyList();
        }
        final int n = Math.max(1, size);
        final int len = s.length();
        List<String> out = new ArrayList<>((len + n - 1) / n);
        for (int i = 0; i < len; i += n) {
            out.add(s.substring(i, Math.min(len, i + n)));
        }
        return out;
    }
}