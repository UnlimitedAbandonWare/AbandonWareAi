package com.example.lms.probe.dto;

/**
 * NightmareBreaker OPEN→HALF_OPEN→CLOSED 전이를 재현하기 위한 관리자용 요청.
 */
public class SoakProbeRequest {

    /** NightmareBreaker key (예: "query-transformer:runLLM" 또는 "soak:test"). */
    public String key = "soak:test";

    /** 반복 횟수 (OPEN→HALF_OPEN→CLOSED) */
    public int cycles = 1;

    /** OPEN 유지 시간(ms). 0 이하이면 현재 설정값을 사용한다. */
    public long openDurationMs = 200;

    /** HALF_OPEN에서 허용할 최대 시도 횟수. 0 이하이면 현재 설정값을 사용한다. */
    public int halfOpenMaxCalls = 2;

    /** HALF_OPEN→CLOSED 전환에 필요한 연속 성공 수. 0 이하이면 현재 설정값을 사용한다. */
    public int halfOpenSuccessThreshold = 2;

    /**
     * HALF_OPEN 구간에서 1회 실패를 주입한 뒤(즉시 OPEN 복귀), 다음 사이클에서 성공으로 닫히는지 확인한다.
     * 기본값은 false (OPEN→HALF_OPEN→CLOSED "성공" 경로 재현).
     */
    public boolean failOnceInHalfOpen = false;
}
