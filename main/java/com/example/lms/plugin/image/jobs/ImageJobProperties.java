package com.example.lms.plugin.image.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * 이미지 작업 처리 큐의 동작을 제어하는 설정 속성입니다.
 * {@code relayDelayMs}는 처리 주기 사이의 고정 지연 시간을 밀리초 단위로 정의합니다.
 * {@code etaSamples}는 대기 중인 작업의 예상 완료 시간을 추정할 때 포함할 최근 작업 기간의 수를 결정합니다.
 */

@ConfigurationProperties(prefix = "image.jobs")
public class ImageJobProperties {

    private long relayDelayMs = 300_000; // 5분
    private int etaSamples = 10;

    public long getRelayDelayMs() { return relayDelayMs; }
    public void setRelayDelayMs(long relayDelayMs) { this.relayDelayMs = relayDelayMs; }
    public int getEtaSamples() { return etaSamples; }
    public void setEtaSamples(int etaSamples) { this.etaSamples = etaSamples; }
    // --- Getters and Setters ---



}