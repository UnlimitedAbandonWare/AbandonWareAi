package com.example.lms.service.search;

import lombok.extern.slf4j.Slf4j;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;




/**
 * 검색 질의의 중의성(예: K8Plus ↔ 자동차)으로 인한 결과 오염을 줄이기 위한
 * 부정 키워드/호스트 프로필을 제공한다.
 *
 * 필요 시 토픽별 프로필을 추가 확장하면 된다.
 */
public final class SearchDisambiguation {

    /** 결과 후처리에 사용할 프로필(부정 키워드 & 차단 호스트). */
    public record Profile(Set<String> negativeKeywords, Set<String> blockedHosts) {}

    // ── K8Plus(미니 PC) 관련 토큰 셋 ──
    private static final Set<String> K8PLUS_TOKENS = Set.of(
            "k8plus", "k8 plus", "k8+", "케이8 플러스", "케이8플러스"
    );

    // 자동차 오염 방지를 위한 부정 키워드
    private static final Set<String> K8PLUS_NEG_KWS = Set.of(
            "기아", "kia", "자동차", "세단", "승용차",
            "엔진", "연비", "시승", "딜러", "보배드림",
            "엔카", "모터", "옵션", "제원", "트림"
    );

    // 자동차 관련 호스트 차단(부분 일치)
    private static final Set<String> K8PLUS_BLOCKED_HOSTS = Set.of(
            "kia.com", "kia.co.kr", "bobaedream", "encar", "auto", "car"
    );
    // ── Galaxy Z Fold 6/7 관련 토큰 셋 ──
    private static final Set<String> ZFOLD_TOKENS = Set.of(
            "갤럭시 z 폴드", "z fold", "z 폴드",
            "fold 6", "fold6", "폴드6",
            "fold 7", "fold7", "폴드7"
    );
    // ── Genshin 전용 오염 제거 ──
    private static final Set<String> GENSHIN_TOKENS = Set.of("원신","genshin","hoyoverse","hoyolab");
    private static final Set<String> GENSHIN_NEG_KWS = Set.of(
            "핵","치트","cheat","hack","minty","shika","스킵","무제한","crack"
    );
    private static final Set<String> GENSHIN_BLOCKED_HOSTS = Set.of(
            "pastebin.com","mega.nz","discord.gg"
    );
    private static final Set<String> ZFOLD_NEG_KWS = Set.of(
            "중고차", "차량", "엔진오일", "자동차"
    );

    private static final Set<String> ZFOLD_BLOCKED_HOSTS = Set.of(
            "encar", "bobaedream"
    );

    // ── Academy/Education profile ──
    // 학원/아카데미/academy 관련 토큰.  질의에 이러한 토큰이 포함되면 교육
    // 프로필을 적용하여 반려동물/차량 관련 키워드를 차단하고 tag 경로를 컷
    // 합니다.
    private static final Set<String> ACADEMY_TOKENS = Set.of(
            "학원", "아카데미", "academy", "교육"
    );
    // 부정 키워드: 교육과 무관한 반려동물/차량/태그 관련 용어
    private static final Set<String> ACADEMY_NEG_KWS = Set.of(
            "강아지", "반려동물", "유기견", "펫택시", "화장터", "주유구", "자동차", "차량", "정비", "태그", "tag", "카센터"
    );
    // 차단 호스트: 저품질 블로그와 태그 페이지
    private static final Set<String> ACADEMY_BLOCKED_HOSTS = Set.of(
            "ohmygod10.com", "messenger1004.com", "blog.messenger1004.com",
            "tistory.com/tag", "blog.naver.com/tag", "category/tag"
    );


    /**
     * 원 질의에 기반하여 적용할 프로필을 결정한다.
     */
    public static Profile resolve(String originalQuery) {

        String q = originalQuery == null ? "" : originalQuery.toLowerCase(Locale.ROOT);
        if (containsAny(q, GENSHIN_TOKENS)) {
            return new Profile(GENSHIN_NEG_KWS, GENSHIN_BLOCKED_HOSTS);
        }
        if (containsAny(q, K8PLUS_TOKENS)) {
            return new Profile(K8PLUS_NEG_KWS, K8PLUS_BLOCKED_HOSTS);
        }
        if (containsAny(q, ZFOLD_TOKENS)) {
            return new Profile(ZFOLD_NEG_KWS, ZFOLD_BLOCKED_HOSTS);
        }
        // Academy/Education profile.  If the query includes academy tokens then
        // apply the education profile to filter out dog/pet/car noise and tag
        // pages.  This helps reduce retrieval contamination when users search
        // for 학원 or 아카데미 topics.  This profile takes precedence over
        // the default empty profile but is applied after more specific
        // profiles such as K8Plus, ZFold and Genshin.
        if (containsAny(q, ACADEMY_TOKENS)) {
            return new Profile(ACADEMY_NEG_KWS, ACADEMY_BLOCKED_HOSTS);
        }
        return new Profile(Collections.emptySet(), Collections.emptySet());
    }

    private static boolean containsAny(String text, Set<String> tokens) {
        if (text == null || text.isBlank()) return false;
        for (String t : tokens) {
            if (text.contains(t)) return true;
        }
        return false;
    }

    private SearchDisambiguation() {}
}