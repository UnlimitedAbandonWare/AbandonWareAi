package com.example.lms.learning.gemini;

import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.dto.learning.TuningJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Optional service for managing supervised tuning jobs on Vertex AI.
 * This implementation acts as a shim and does not interact with Vertex AI.
 */
@Service
@RequiredArgsConstructor
public class GeminiTuningService {
    private static final Logger log = LoggerFactory.getLogger(GeminiTuningService.class);

    private final GeminiClient geminiClient;

    public String startTuningJob(TuningJobRequest request) {
        return geminiClient.startTuningJob(request);
    }

    public TuningJobStatus getTuningJobStatus(String jobId) {
        return geminiClient.getTuningJobStatus(jobId);
    }
}