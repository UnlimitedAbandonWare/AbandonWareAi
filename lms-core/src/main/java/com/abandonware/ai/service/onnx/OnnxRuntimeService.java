package com.abandonware.ai.service.onnx;

import ai.onnxruntime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.onnx.OnnxRuntimeService
 * Role: service
 * Feature Flags: onnx.enabled
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.onnx.OnnxRuntimeService
role: service
flags: [onnx.enabled]
*/
public class OnnxRuntimeService {
  private final Semaphore sem;


    @Value("${onnx.enabled:false}")
    private boolean enabled;

    @Value("${onnx.model.path:}")
    private String modelPath;

    private OrtEnvironment env;
    private OrtSession session;

    @PostConstruct
    public void init() throws Exception {
        if (!enabled) return;
        this.env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
    }

    public boolean isReady() {
        return enabled && session != null;
    }

    public float[] scoreBatch(long[][] inputIds, long[][] attnMask, long[][] tokenTypeIds) throws Exception {
        if (!isReady()) throw new IllegalStateException("ONNX not ready");

        try (OnnxTensor ids = OnnxTensor.createTensor(env, toBuffer(inputIds), new long[]{inputIds.length, inputIds[0].length});
             OnnxTensor attn = OnnxTensor.createTensor(env, toBuffer(attnMask), new long[]{attnMask.length, attnMask[0].length});
             OnnxTensor tok = OnnxTensor.createTensor(env, toBuffer(tokenTypeIds), new long[]{tokenTypeIds.length, tokenTypeIds[0].length})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", ids);
            inputs.put("attention_mask", attn);
            inputs.put("token_type_ids", tok);

            try (OrtSession.Result out = session.run(inputs)) {
                float[][] logits = (float[][]) out.get(0).getValue(); // [batch, 1] assumed
                float[] s = new float[logits.length];
                for (int i = 0; i < logits.length; i++) s[i] = logits[i][0];
                return s;
            }
        }
    }

    private static LongBuffer toBuffer(long[][] arr) {
        int rows = arr.length, cols = arr[0].length;
        LongBuffer buf = LongBuffer.allocate(rows * cols);
        for (long[] row : arr) buf.put(row);
        buf.rewind();
        return buf;
    }
}

public OnnxRuntimeService(@org.springframework.beans.factory.annotation.Value("${onnx.max-concurrency:2}") int maxConc){
  this.sem = new Semaphore(Math.max(1, maxConc));
}