package ai.abandonware.nova.orch.router;

import com.example.lms.llm.ModelRuntimeHealthTracker;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 로컬 Ollama 라우트의 (installed model, endpoint) 단위 in-flight admission 슬롯.
 *
 * <p>선택된 로컬 모델이 다른 in-flight 요청으로 이미 사용 중이면 invoke를 큐잉하지 않고
 * 라우터가 기존 폴백 선택 경로(reason=local_contended)로 우회하도록 한다.
 * reranker/ONNX/web 세마포어와는 별개 레이어 — LLM Ollama 슬롯 전용.</p>
 */
public final class LocalModelAdmission {

    private final ConcurrentMap<String, Semaphore> slots = new ConcurrentHashMap<>();

    /** (endpoint host:port hash, installed model name) 복합 슬롯 키. */
    public static String slotKey(String baseUrl, String modelName) {
        String endpoint = ModelRuntimeHealthTracker.endpointIdentityHash(baseUrl);
        String model = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
        return endpoint + ":" + model;
    }

    /** 슬롯 포화 여부 peek — 허가를 소비하지 않는다 (route 시점 우회 판단용). */
    public boolean saturated(String slotKey, int permits) {
        return slot(slotKey, permits).availablePermits() <= 0;
    }

    /** invoke 직전 non-blocking admission. */
    public boolean tryAcquire(String slotKey, int permits) {
        return slot(slotKey, permits).tryAcquire();
    }

    /** tryAcquire 성공분만 반환한다 (호출측 finally에서 사용). */
    public void release(String slotKey) {
        Semaphore semaphore = slots.get(slotKey);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    private Semaphore slot(String slotKey, int permits) {
        return slots.computeIfAbsent(slotKey, key -> new Semaphore(Math.max(1, permits), true));
    }
}
