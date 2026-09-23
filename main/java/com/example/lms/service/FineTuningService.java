package com.example.lms.service;

import com.example.lms.dto.FineTuningJobDto;
import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.TrainingSampleRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.util.TokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FineTuningService {
    private static final Logger log = LoggerFactory.getLogger(FineTuningService.class);
    private static final String REMOTE_DISABLED_REASON = "remote_fine_tuning_endpoint_unavailable";

    private final TrainingSampleRepository trainingSampleRepo;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;
    private final CurrentModelRepository currentModelRepo;

    public boolean isRemoteFineTuningDisabled() {
        return true;
    }

    public String remoteDisabledReason() {
        return REMOTE_DISABLED_REASON;
    }

    public String startFineTuningJob(FineTuningOptionsDto opts) throws IOException {
        traceRemoteDisabled();
        log.warn("[AWX][fine-tuning] provider-disabled disabledReason={}", remoteDisabledReason());
        return null;
    }

    public List<FineTuningJobDto> listFineTuningJobs() {
        return List.of();
    }

    public Optional<FineTuningJobDto> checkJobStatus(String jobId) {
        return Optional.empty();
    }

    private static void traceRemoteDisabled() {
        TraceStore.put("fineTuning.providerDisabled", true);
        TraceStore.put("fineTuning.disabledReason", REMOTE_DISABLED_REASON);
        TraceStore.put("fineTuning.skipped.reason", REMOTE_DISABLED_REASON);
    }
}
