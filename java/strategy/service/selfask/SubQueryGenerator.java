package strategy.service.selfask;

import java.util.Map;

public class SubQueryGenerator {
  public Map<SubQueryType, String> generate(String q) {
    return Map.of(
      SubQueryType.BQ, "정의/도메인 관점으로 재질문: " + q,
      SubQueryType.ER, "동의어·별칭·오타 보정 관점 재질문: " + q,
      SubQueryType.RC, "관계·가설 검증 관점 재질문: " + q
    );
  }
}