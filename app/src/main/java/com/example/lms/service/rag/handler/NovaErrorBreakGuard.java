package com.example.lms.service.rag.handler;

import com.example.lms.cfvm.NovaErrorBreak;
import com.example.lms.cfvm.NovaErrorBreak.Level;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;

@RequiredArgsConstructor
@Order(0)
public class NovaErrorBreakGuard implements RetrievalHandler {
  private final NovaErrorBreak breakEngine;

  @Override
  public Context handle(Context ctx, Chain chain) {
    NovaErrorBreak.Decision dec = breakEngine.evaluate(ctx.features());
    ctx.put("nova.errorbreak.level", dec.level().name());

    if (dec.level()==Level.WARN) {
      ctx.put("officialSourcesOnly", true);
      ctx.put("onnx.enabled", false);
      ctx.put("fusion.rrf.weight.scale", 0.85);
      // cap K if present, or set conservative defaults
      Object wk = ctx.get("webTopK");
      Object vk = ctx.get("vectorTopK");
      ctx.put("webTopK", wk instanceof Number ? Math.min(((Number)wk).intValue(), 8) : 8);
      ctx.put("vectorTopK", vk instanceof Number ? Math.min(((Number)vk).intValue(), 6) : 6);
      ctx.put("onnx.max-concurrency", 1);
    } else if (dec.level()==Level.BREAK) {
      ctx.put("officialSourcesOnly", true);
      ctx.put("extremez.enabled", false);
      ctx.put("planner.plan", "zero_break.v1.yaml");
      ctx.put("webTopK", 3);
      ctx.put("vectorTopK", 4);
      ctx.put("cache.first", true);
      ctx.put("onnx.max-concurrency", 1);
      ctx.put("naver.search.timeout-ms", 4000);
    }
    return chain.next(ctx);
  }
}