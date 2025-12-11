package service;

import trace.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal placeholder for hedged requests configuration and shared helpers
 * used by legacy/demo services. This class is intentionally lightweight and
 * does not perform real HTTP calls; the canonical implementation lives in
 * {@code com.example.lms.service.NaverSearchService}.
 *
 * <p>
 * 이 클래스는 다음과 같은 용도로 사용됩니다.
 * </p>
 * <ul>
 * <li>테스트/데모 코드에서 NaverSearchService API 의존성을 만족시키기 위한 Stub</li>
 * <li>버전/패치 질의 감지(isGamePatchQuery)와 키 파싱(resolveKeys)을 재사용하기 위한 헬퍼</li>
 * </ul>
 */
public class NaverSearchService {

    /** 버전 문자열(예: 1.0, v2.1.3 등)을 감지하기 위한 패턴 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("v?\\d+(\\.\\d+)+");

    @Autowired
    private GuardProfileProps guardProfileProps;

    private boolean hedgeEnabled = true;
    private int hedgeDelayMs = 120;
    private int timeoutMs = 40000;

    // NAVER 키 설정 (CSV 및 단일 client-id/secret 브리지)
    private String naverKeysCsv;
    private String naverClientId;
    private String naverClientSecret;

    /**
     * 단순 키 레코드. 실제 HTTP 호출은 canonical 서비스에서 수행한다.
     */
    public record ApiKey(String clientId, String clientSecret) {
    }

    public void configure(boolean hedgeEnabled, int hedgeDelayMs, int timeoutMs) {
        this.hedgeEnabled = hedgeEnabled;
        this.hedgeDelayMs = hedgeDelayMs;
        this.timeoutMs = timeoutMs;
    }

    /**
     * naver.keys CSV 및 client-id/secret 환경 변수를 바탕으로
     * 사용 가능한 키 목록을 구성한다.
     *
     * <p>
     * 포맷:
     * </p>
     * <ul>
     * <li>{@code id:secret}</li>
     * <li>{@code id;secret} (세미콜론도 허용)</li>
     * <li>여러 개의 키는 콤마(,)로 구분</li>
     * </ul>
     */
    public List<ApiKey> resolveKeys() {
        List<ApiKey> keys = new ArrayList<>();

        if (naverKeysCsv != null && !naverKeysCsv.isBlank()) {
            String[] tokens = naverKeysCsv.split(",");
            for (String raw : tokens) {
                if (raw == null)
                    continue;
                String s = raw.trim();
                if (s.isEmpty())
                    continue;
                // 세미콜론/콜론 혼용 허용
                s = s.replace(";", ":");
                if (!s.contains(":")) {
                    continue;
                }
                String[] parts = s.split(":", 2);
                String id = parts[0].trim();
                String secret = parts[1].trim();
                if (!id.isEmpty() && !secret.isEmpty()) {
                    keys.add(new ApiKey(id, secret));
                }
            }
        }

        // CSV 파싱 결과가 없고, client-id/secret가 있을 때 브리지
        if (keys.isEmpty()
                && naverClientId != null && !naverClientId.isBlank()
                && naverClientSecret != null && !naverClientSecret.isBlank()) {
            keys.add(new ApiKey(naverClientId.trim(), naverClientSecret.trim()));
        }

        return keys;
    }

    /**
     * 게임/소프트웨어 패치 질의인지 단순 휴리스틱으로 판별한다.
     * 특정 게임 이름(원신 등)에 종속되지 않고, "패치/업데이트/버전" 키워드와
     * 버전 패턴(숫자.숫자)이 함께 등장하면 true를 반환한다.
     */
    public boolean isGamePatchQuery(String query) {
        if (query == null || query.isBlank())
            return false;
        String q = query.toLowerCase(java.util.Locale.ROOT);

        boolean hasPatchKeyword = q.contains("패치") || q.contains("patch") ||
                q.contains("업데이트") || q.contains("update") ||
                q.contains("버전") || q.contains("version");

        if (!hasPatchKeyword) {
            return false;
        }

        return VERSION_PATTERN.matcher(q).find();
    }

    // 단순 접근자 (필요 시 테스트 코드에서 사용)
    public boolean isHedgeEnabled() {
        return hedgeEnabled;
    }

    public int getHedgeDelayMs() {
        return hedgeDelayMs;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setNaverKeysCsv(String naverKeysCsv) {
        this.naverKeysCsv = naverKeysCsv;
    }

    public void setNaverClientId(String naverClientId) {
        this.naverClientId = naverClientId;
    }

    public void setNaverClientSecret(String naverClientSecret) {
        this.naverClientSecret = naverClientSecret;
    }
}

// PATCH_MARKER: NaverSearchService updated per latest spec.
