

        package com.example.lms.service.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxValue;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;




/**
 * Service that encapsulates a local ONNX runtime session for cross-encoder inference.
 *
 * <p>This service is responsible for loading a serialized ONNX model from a
 * configurable location and providing a simple {@link #predict(String[], String[])}
 * API to score query-document pairs. In the absence of a domain-specific
 * tokeniser and model, this implementation falls back to a lightweight
 * lexical similarity metric. The ONNX session is initialised in
 * {@link #init()} and remains active for the lifetime of the application.</p>
 */
@Service
// 개선: onnx 백엔드일 때에만 서비스 생성(선택)
@ConditionalOnProperty(
        prefix = "abandonware.reranker", name = "backend", havingValue = "onnx-runtime"
)
public class OnnxRuntimeService {

    // 개선: 로거 추가
    private static final Logger log = LoggerFactory.getLogger(OnnxRuntimeService.class);

    /**
     * Model file path.  Supports both the newer abandonware property and the
     * legacy {@code onnx.model-path} key for backwards compatibility.  The
     * nested shim resolves the abandonware key first and falls back to
     * the legacy key when unspecified.
     */
    @Value("${abandonware.reranker.onnx.model-path:${onnx.model-path:}}")
    private String modelPath;

    /**
     * Execution provider name (e.g. cpu, cuda, tensorrt).  Supports
     * resolution via both abandonware and legacy keys.  Defaults to
     * {@code cuda}.
     */
    @Value("${abandonware.reranker.onnx.execution-provider:${onnx.execution-provider:cuda}}")
    private String executionProvider;

    /** Optional CUDA device selection; -1 = default device */
    @Value("${abandonware.reranker.onnx.device-id:${onnx.device-id:${zsys.onnx.gpu-id:-1}}}")
    private int deviceId;

    private OrtEnvironment env;
    private OrtSession session;
    /**
     * Indicates whether an ONNX model has been successfully loaded. When
     * {@code false} the service will always fall back to lexical scoring.
     */
    private volatile boolean available;

    /**
     * Path to the vocabulary used by the wordpiece tokenizer.  Supports
     * resolution via abandonware and legacy keys.  When unspecified the
     * tokenizer will be disabled and lexical scoring will be used.
     */
    @Value("${abandonware.reranker.onnx.vocab-path:${onnx.vocab-path:}}")
    private String vocabPath;

    /**
     * Maximum sequence length for tokenised inputs.  Longer sequences are
     * truncated.  Supports both abandonware and legacy keys.  Defaults to
     * 256.
     */
    @Value("${abandonware.reranker.onnx.max-seq-len:${onnx.max-seq-len:256}}")
    private int maxSeqLen;

    /** 안전 차단: 토크나이저 투입 전 원문 하드 컷(문자 단위) */
    @Value("${abandonware.reranker.onnx.max-chars:800}")
    private int maxChars;

    /**
     * Whether to normalise raw model logits via a sigmoid function.  When
     * enabled the first element of the output tensor is passed through
     * {@code 1/(1+e^-x)} to produce a value in the range [0,1].  Defaults
     * to {@code true}.  Only applied when the ONNX backend is active.
     */
    @Value("${abandonware.reranker.onnx.normalize:true}")
    private boolean normalize;

    // Fail-closed: false면 폴백 없이 즉시 예외
    @Value("${abandonware.reranker.onnx.fallback-enabled:true}")
    private boolean fallbackEnabled;

    // Names of model inputs and outputs captured during session initialisation
    private java.util.List<String> inputNames;
    private java.util.List<String> outputNames;

    /** Tokeniser used to convert query/document pairs into model inputs. */
    private com.example.lms.service.onnx.tokenizer.CrossEncoderTokenizer tokenizer;

    /**
     * Initialise the ONNX environment and session. If the model path is
     * configured with a {@code classpath:} prefix it will be resolved from
     * the application resources; otherwise it will be treated as a file
     * system path. Execution provider selection is based on the
     * {@link #executionProvider} property: {@code cpu} uses the default
     * provider, {@code cuda} enables CUDA support and {@code tensorrt} uses
     * TensorRT when available.
     */
    @PostConstruct
    public void init() {
        try {
            // If no model path is provided there is nothing to initialise.  Leave
            // available=false so callers will fall back to lexical scoring.
            if (modelPath == null || modelPath.isBlank()) {
                this.available = false;
                // fail-closed: throw when fallback is disabled
                if (!fallbackEnabled) {
                    throw new IllegalStateException("ONNX model path missing and fallback disabled");
                }
                return;
            }
            // Attempt to load the vocabulary for the wordpiece tokenizer.  When
            // absent or failing to read the tokenizer will remain null and
            // lexical scoring will be used.  Suppress any exceptions.
            if (vocabPath != null && !vocabPath.isBlank()) {
                try (InputStream vs = open(vocabPath)) {
                    if (vs != null) {
                        this.tokenizer = new com.example.lms.service.onnx.tokenizer.WordpieceTokenizer(vs);
                    }
                } catch (Exception e) {
                    this.tokenizer = null;
                    // fail-closed: abort initialisation when fallback disabled
                    if (!fallbackEnabled) {
                        throw new IllegalStateException("ONNX vocab load failed and fallback disabled", e);
                    }
                }
            }
            // Always obtain an OrtEnvironment.  This does not allocate native
            // resources until a session is created.
            this.env = OrtEnvironment.getEnvironment();
            // Open and read the model bytes.  Use InputStream so classpath: is
            // supported in both exploded and packaged modes.
            try (InputStream m = open(modelPath)) {
                if (m == null) {
                    this.available = false;
                    this.session = null;
                    log.warn("[ONNX] model-path not resolvable: {}", modelPath);
                    if (!fallbackEnabled) {
                        throw new IllegalStateException("ONNX model path not resolvable and fallback disabled");
                    }
                    return;
                }
                byte[] bytes = m.readAllBytes();
                // Attempt to create an in-memory session.  If this fails the
                // session remains null but we still mark available=true so that
                // scorePair() can compute alternative scores.
                try {
                    OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                    String ep = (executionProvider == null ? "cuda" : executionProvider.trim().toLowerCase());
                    try {
                        if ("cuda".equals(ep)) {
                            if (deviceId >= 0) {
                                opts.addCUDA(deviceId);
                                log.info("[ONNX] CUDA EP enabled (deviceId={})", deviceId);
                            } else {
                                opts.addCUDA();
                                log.info("[ONNX] CUDA EP enabled (default device)");
                            }
                        } else {
                            log.info("[ONNX] CPU EP selected (provider={})", ep);
                        }
                        this.session = env.createSession(bytes, opts);
                    } catch (Throwable t) {
                        log.warn("[ONNX] CUDA init failed → falling back to CPU: {}", t.toString());
                        this.session = env.createSession(bytes, new OrtSession.SessionOptions());
                    }
                } catch (Throwable ignore) {
                    this.session = null;
                }
            }
            // Consider the service available only when a valid session exists.
            this.available = (this.session != null);
            // capture input and output names when a session has been created
            if (this.session != null) {
                this.inputNames  = new java.util.ArrayList<>(this.session.getInputInfo().keySet());
                this.outputNames = new java.util.ArrayList<>(this.session.getOutputInfo().keySet());
            }
            log.info("[ONNX] model initialised: {}", modelPath);
        } catch (Throwable t) {
            this.available = false;
            this.session = null;
            log.warn("[ONNX] initialisation failed, falling back (reason: {})", t.toString());
            // fail-closed: rethrow when fallback disabled
            if (!fallbackEnabled) {
                throw new IllegalStateException("ONNX initialisation failed and fallback disabled", t);
            }
        }
    }

    /**
     * Resolve a model path that may be prefixed with {@code classpath:}.
     *
     * @param path the configured path
     * @return a file system path suitable for {@link OrtSession#createSession(String, OrtSession.SessionOptions)}
     */
    // 수정: throws 제거, 내부에서 안전 처리
    private String resolveModelPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        String prefix = "classpath:";
        if (trimmed.startsWith(prefix)) {
            String location = trimmed.substring(prefix.length());
            Resource resource = new ClassPathResource(location);
            if (!resource.exists()) {
                log.warn("[ONNX] classpath resource not found: {}", location);
                return null;
            }
            try {
                // 개발환경(Exploded)에서는 파일 경로가 나옴
                return resource.getFile().getAbsolutePath();
            } catch (IOException e) {
                // 패키징(JAR)된 경우 getFile() 불가 → 여기서는 로드 건너뛰고 폴백
                log.warn("[ONNX] resource is not a file (likely inside JAR). Skipping ONNX load.");
                return null;
            }
        }
        return trimmed;
    }

    /**
     * Open an input stream from a path that may be prefixed with
     * {@code classpath:}.  When the prefix is present the resource is
     * resolved from the Spring classpath.  When the prefix is absent the
     * argument is treated as a file system location.  If the resource
     * cannot be resolved {@code null} is returned.
     *
     * @param path the location to open
     * @return an {@link InputStream} or {@code null} if not found
     */
    private InputStream open(String path) {
        if (path == null || path.isBlank()) return null;
        String trimmed = path.trim();
        String prefix = "classpath:";
        try {
            if (trimmed.startsWith(prefix)) {
                String location = trimmed.substring(prefix.length());
                Resource resource = new ClassPathResource(location);
                if (!resource.exists()) return null;
                return resource.getInputStream();
            }
            // file system path
            return new java.io.FileInputStream(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Return {@code true} when a model has been initialised.  Note that the
     * underlying OrtSession may be {@code null} if the model failed to
     * initialise, but callers can still rely on this flag to indicate that
     * alternative scoring (e.g. inverted lexical similarity) should be used.
     */
    public boolean available() {
        return available;
    }

    /**
     * Alias for {@link #available()}.  Some callers check the service state
     * via {@code active()} rather than {@code available()}.  To preserve
     * backwards compatibility we expose this additional accessor.
     *
     * @return {@code true} when the ONNX model session has been successfully
     *         initialised, {@code false} otherwise
     */
    public boolean active() {
        return available;
    }

    /**
     * Predict confidence scores for a batch of query-document pairs.
     *
     * <p>The returned matrix has shape {@code [queries.length][documents.length]}. Each entry
     * represents a similarity score between the corresponding query and document. If an ONNX
     * model is available and compatible with the input format, it can be invoked here. In the
     * absence of such a model, a simple Jaccard similarity over whitespace-delimited tokens
     * is used as a fallback. The output scores are in the range {@code [0,1]}.</p>
     *
     * @param queries   an array of query strings
     * @param documents an array of document strings
     * @return a 2D float array of confidence scores
     */
    public float[][] predict(String[] queries, String[] documents) {
        int m = (queries == null ? 0 : queries.length);
        int n = (documents == null ? 0 : documents.length);
        float[][] result = new float[m][n];
        if (m == 0 || n == 0) {
            return result;
        }
        // If the model is not available or the session failed to load, fall back to
        // lexical Jaccard similarity.  When the model is available use the
        // scorePair API to compute a similarity score for each query/document
        // combination.  scorePair() already handles model invocation and
        // fallback internally, returning a double in the range [0,1].
        boolean useModel = available && session != null;
        for (int i = 0; i < m; i++) {
            String q = queries[i] == null ? "" : queries[i];
            for (int j = 0; j < n; j++) {
                String d = documents[j] == null ? "" : documents[j];
                if (useModel) {
                    result[i][j] = (float) scorePair(q, d);
                } else {
                    result[i][j] = computeJaccardSimilarity(q, d);
                }
            }
        }
        return result;
    }

    /**
     * Compute a simple Jaccard similarity between two strings. The strings are
     * tokenised on non-word characters and converted to lower case. The
     * similarity is defined as {@code |intersection| / |union|}. When both
     * strings are empty the similarity defaults to {@code 0.0f}.
     *
     * @param q the query string
     * @param d the document string
     * @return a similarity score in {@code [0,1]}
     */
    private float computeJaccardSimilarity(String q, String d) {
        if (q == null || d == null) {
            return 0.0f;
        }
        String[] qTokens = Arrays.stream(q.toLowerCase().split("\\W+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        String[] dTokens = Arrays.stream(d.toLowerCase().split("\\W+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (qTokens.length == 0 || dTokens.length == 0) {
            return 0.0f;
        }
        Set<String> qSet = new HashSet<>(Arrays.asList(qTokens));
        Set<String> dSet = new HashSet<>(Arrays.asList(dTokens));
        Set<String> intersection = new HashSet<>(qSet);
        intersection.retainAll(dSet);
        Set<String> union = new HashSet<>(qSet);
        union.addAll(dSet);
        if (union.isEmpty()) {
            return 0.0f;
        }
        return (float) intersection.size() / (float) union.size();
    }

    /**
     * Simple container for encoded inputs.  Each field corresponds to a model
     * input: {@code inputIds}, {@code attn} (attention mask) and
     * {@code tokenTypes} (segment IDs).  This record mirrors the
     * {@link com.example.lms.service.onnx.tokenizer.CrossEncoderTokenizer.Encoded} record but
     * exists here to avoid leaking the external type.
     */
    private record Enc(int[] inputIds, int[] attn, int[] tokenTypes) {}

    /**
     * Encode a query/document pair into integer arrays suitable for feeding to
     * the ONNX model.  When no tokenizer is available this returns empty
     * arrays to signal fallback.
     */
    private Enc encodePair(String q, String d, int maxSeq) {
        if (this.tokenizer == null) {
            return new Enc(new int[0], new int[0], new int[0]);
        }
        try {
            var enc = this.tokenizer.encodePair(q == null ? "" : q, d == null ? "" : d, maxSeq);
            // Accessors follow record component names in Encoded record
            java.lang.reflect.Method mInput = enc.getClass().getMethod("inputIds");
            java.lang.reflect.Method mAttn  = enc.getClass().getMethod("attentionMask");
            java.lang.reflect.Method mType  = enc.getClass().getMethod("tokenTypeIds");
            int[] ids  = (int[]) mInput.invoke(enc);
            int[] attn = (int[]) mAttn.invoke(enc);
            int[] tt   = (int[]) mType.invoke(enc);
            return new Enc(ids, attn, tt);
        } catch (Exception e) {
            return new Enc(new int[0], new int[0], new int[0]);
        }
    }

    /** Convert an int[] to a long[] for ONNX tensor creation. */
    private long[] toLong(int[] ints) {
        long[] arr = new long[ints.length];
        for (int i = 0; i < ints.length; i++) arr[i] = ints[i];
        return arr;
    }

    /** Pick the first matching name from a set of input names. */
    private String pick(java.util.Set<String> names, String preferred) {
        if (names.contains(preferred)) return preferred;
        // fall back to the first name
        return names.iterator().next();
    }

    /** Flatten a tensor of shape [1, n] or [n] to a float array. */
    private float[] flatten(OnnxTensor t) throws Exception {
        Object v = t.getValue();
        if (v instanceof float[]) {
            return (float[]) v;
        }
        if (v instanceof float[][] arr) {
            return arr.length > 0 ? arr[0] : new float[0];
        }
        return new float[0];
    }

    /**
     * Compute a similarity score for a single query/document pair.  When the
     * ONNX model is available and the session has been initialised the score
     * returned is derived from the model output (when possible).  When the
     * model cannot be invoked or is not available the lexical Jaccard
     * similarity is used.  To help drive mixture-of-experts escalation a
     * simple transformation is applied when the model is available: the
     * returned score is {@code 1 - lexicalSimilarity}.  This inversion
     * encourages diversity relative to the embedding reranker.
     */
    public double scorePair(String query, String document) {
        // 하드 컷으로 전처리(토큰화/런타임 비용 절감)
        if (query != null && query.length() > maxChars) {
            query = query.substring(0, maxChars);
        }
        if (document != null && document.length() > maxChars) {
            document = document.substring(0, maxChars);
        }
        double lex = computeJaccardSimilarity(query == null ? "" : query, document == null ? "" : document);
        // If no model is available return lexical similarity directly
        if (!available) {
            return lex;
        }
        // When a valid ONNX session exists attempt to run inference.  If any
        // exception is thrown the code falls back to the inverted lexical score.
        if (session != null) {
            try {
                Enc enc = encodePair(query, document, maxSeqLen);
                if (enc.inputIds.length > 0) {
                    long[][] inputIds = new long[][] { toLong(enc.inputIds) };
                    long[][] attn     = new long[][] { toLong(enc.attn) };
                    long[][] tt       = new long[][] { toLong(enc.tokenTypes) };
                    java.util.Map<String, OnnxTensor> in = new java.util.HashMap<>();
                    java.util.Set<String> names = session.getInputInfo().keySet();
                    String idName = pick(names, "input_ids");
                    in.put(idName, OnnxTensor.createTensor(env, inputIds));
                    if (names.contains("attention_mask")) in.put("attention_mask", OnnxTensor.createTensor(env, attn));
                    if (names.contains("token_type_ids")) in.put("token_type_ids", OnnxTensor.createTensor(env, tt));
                    // 입력 텐서 수동 close를 위해 try/finally 사용
                    OnnxTensor t0 = in.get(idName);
                    OnnxTensor t1 = in.get("attention_mask");
                    OnnxTensor t2 = in.get("token_type_ids");
                    try (OrtSession.Result out = session.run(in)) {
                        for (String k : session.getOutputInfo().keySet()) {
                            var vOpt = out.get(k); // Optional<OnnxValue>
                            var v = vOpt.orElse(null);
                            if (v instanceof OnnxTensor t) {
                                float[] flat = flatten(t);
                                    if (flat.length > 0) {
                                    float raw = flat[0];
                                    if (normalize) {
                                        // Apply sigmoid normalisation to map raw logits into [0,1].  In
                                        // many cross-encoder architectures the output is an unbounded
                                        // activation which benefits from a logistic transform.  Guard
                                        // against overflow by clamping extremely large magnitudes.
                                        double x = Math.max(-50.0, Math.min(50.0, raw));
                                        double sigmoid = 1.0 / (1.0 + Math.exp(-x));
                                        return (float) sigmoid;
                                    }
                                    return raw;
                                }
                            }
                        }
                    } finally {
                        if (t2 != null) try { t2.close(); } catch (Exception ignore) {}
                        if (t1 != null) try { t1.close(); } catch (Exception ignore) {}
                        if (t0 != null) try { t0.close(); } catch (Exception ignore) {}
                    }
                }
            } catch (Throwable ignore) {
                // ignore and fall back
            }
        }
        // When a model is present but inference failed return an inverted lexical score.
        return 1.0 - lex;
    }

    // --- Accessors for health checks and configuration ---

    /**
     * Indicates whether the ONNX runtime is available (model loaded).
     *
     * @return true when the model session has been initialised, false otherwise
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Indicates whether fallback scoring is enabled. When false the service
     * will throw exceptions instead of silently falling back to lexical scoring.
     *
     * @return true when fallback is enabled, false otherwise
     */
    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    /**
     * Indicates whether sigmoid normalisation is enabled for ONNX outputs.
     *
     * @return true when normalisation is enabled, false otherwise
     */
    public boolean isNormalizeEnabled() {
        return normalize;
    }

    /**
     * Exposes the configured execution provider (cpu, cuda, etc).
     *
     * @return the execution provider string
     */
    public String getExecutionProvider() {
        return executionProvider;
    }

    /**
     * Returns the maximum sequence length used for tokenisation.
     *
     * @return max sequence length
     */
    public int getMaxSeqLen() {
        return maxSeqLen;
    }

    /**
     * Returns the list of input tensor names if the model session is available.
     *
     * @return list of input names or null
     */
    public java.util.List<String> getInputNames() {
        return inputNames;
    }

    /**
     * Returns the list of output tensor names if the model session is available.
     *
     * @return list of output names or null
     */
    public java.util.List<String> getOutputNames() {
        return outputNames;
    }
}