package com.abandonware.ai.agent.onnx;

import ai.onnxruntime.OnnxRuntime;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class OnnxRuntimeService {

    private volatile OrtEnvironment env;
    private volatile OrtSession session;

    public synchronized void initialize() throws Exception {
        if (session != null) return;
        String path = System.getProperty("onnx.model.path", "");
        if (path == null || path.isBlank()) return;
        if (!Files.exists(Path.of(path))) return;
        env = OrtEnvironment.getEnvironment();
        SessionOptions opts = new SessionOptions();
        // NOTE: keep CPU by default; advanced GPU options can be added here
        session = env.createSession(path, opts);
        System.out.println("[OnnxRuntimeService] Loaded model from " + path);
    }

    public Optional<OrtSession> getSession() {
        return Optional.ofNullable(session);
    }
}
