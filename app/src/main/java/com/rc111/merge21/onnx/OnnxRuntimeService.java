package com.rc111.merge21.onnx;

import org.springframework.beans.factory.ObjectProvider;
import java.util.concurrent.Semaphore;
import ai.onnxruntime.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OnnxRuntimeService implements DisposableBean {
    public OnnxRuntimeService(ObjectProvider<Semaphore> onnxLimiterProvider){ this.onnxLimiterProvider = onnxLimiterProvider; }

    private final ObjectProvider<Semaphore> onnxLimiterProvider;

    private double sigmoid(double x){ return 1.0/(1.0+Math.exp(-12.0*(x-0.0))); }

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final Map<String, OrtSession> sessions = new ConcurrentHashMap<>();

    @Value("${onnx.providers:CPU}")
    private String providers; // CPU|CUDA|DIRECTML

    public OrtSession session(String modelPath) throws OrtException {
        return sessions.computeIfAbsent(modelPath, p -> {
            try {
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                if (providers != null && providers.toUpperCase().contains("CUDA")) {
                    opts.addCUDA();
                }
                return env.createSession(p, opts);
            } catch (OrtException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void destroy() {
        for (OrtSession s : sessions.values()) try { s.close(); } catch (Exception ignore) {}
        try { env.close(); } catch (Exception ignore) {}
    }
        } finally {
            if (acquired) try { sem.release(); } catch(Exception ignore){}
        }
    }
