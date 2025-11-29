package com.abandonware.ai.service.rag.overdrive;

import org.springframework.stereotype.Component;
import java.util.List;
import com.example.lms.service.rag.overdrive.OverdriveGuard;

@Component
public final class AngerOverdriveGuard {

  private final OverdriveGuard base;

  public AngerOverdriveGuard(OverdriveGuard base) { this.base = base; }

  /** candidates: 1차 후보 컨텍스트, intentScore: 목표 명료도(0~1) */
  public boolean shouldFire(List<?> candidates, double intentScore) {
    if (intentScore < 0.6) return false;        // “명확한 한 방” 조건
    return base.shouldFire(candidates);         // 희소성/권위/모순 신호는 기존 계산 사용
  }
}
