// src/main/java/com/example/lms/domain/enums/SourceCredibility.java
package com.example.lms.domain.enums;

/**
 * 컨텍스트 출처의 신뢰도 수준을 정의합니다.
 * <p>
 * RAG 시스템이 수집한 정보(Snippet)의 출처가 얼마나 신뢰할 수 있는지 분류하는 데 사용됩니다.
 * 이 정보는 LLM의 답변 가중치를 조절하거나, 특정 신뢰도 이하의 정보를 필터링하는 데 활용될 수 있습니다.
 * </p>
 */
public enum SourceCredibility {

    /**
     * 1차 공식 출처: 정부, 게임 제작사, 공식 기술 문서, 공식 블로그 등.
     * 가장 높은 신뢰도를 가집니다.
     */
    OFFICIAL,

    /**
     * 신뢰도 높은 위키: 공식 자료는 아니지만, 잘 관리되고 검증된 정보를 제공하는 위키피디아, 나무위키, Fandom 등.
     * 공식 다음으로 높은 신뢰도를 가집니다.
     */
    RELIABLE_WIKI,

    /**
     * 커뮤니티 주도 정보: 활발한 커뮤니티(예: Reddit, DCInside)나 전문 블로그에서 생성된 정보.
     * 유용할 수 있으나 검증이 필요합니다.
     */
    COMMUNITY_DRIVEN,

    /**
     * 팬 창작물 또는 추측성 정보: 개인 팬 페이지, 확인되지 않은 루머, 추측에 기반한 내용.
     * 신뢰도가 낮아 주의가 필요합니다.
     */
    FAN_MADE_SPECULATION,

    /**
     * 상충되는 정보: 둘 이상의 출처에서 서로 모순되거나 상반된 정보를 제공하는 경우.
     * 사실 확인이 반드시 필요합니다.
     */
    CONFLICTING,

    /**
     * 알 수 없음: 출처의 신뢰도를 판단할 수 없는 경우.
     * 기본값으로 사용되며, 가장 낮은 신뢰도로 취급됩니다.
     */
    UNKNOWN
}