package com.example.lms.learning.gemini;

import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.dto.learning.TuningJobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


/**
 * Optional service for managing supervised tuning jobs on Vertex AI.
 * This implementation acts as a shim and does not interact with Vertex AI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiTuningService {

    private final GeminiClient geminiClient;

    public String startTuningJob(TuningJobRequest request) {
        return geminiClient.startTuningJob(request);
    }

    public TuningJobStatus getTuningJobStatus(String jobId) {
        return geminiClient.getTuningJobStatus(jobId);
    }
}