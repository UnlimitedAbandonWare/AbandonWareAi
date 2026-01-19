package com.example.lms.probe;

import com.example.lms.probe.dto.*;
import java.util.*;

public interface SearchProbeService {
    SearchProbeResponse run(SearchProbeRequest req);
}

class DefaultSearchProbeService implements SearchProbeService {
    private final ProbePipeline pipeline;
    public DefaultSearchProbeService(ProbePipeline pipeline){ this.pipeline = pipeline; }

    @Override public SearchProbeResponse run(SearchProbeRequest req) {
        return pipeline.execute(req);
    }
}

/** 실제 동적 체인 어댑터. 운영에서는 프로젝트 체인을 연결 */
interface ProbePipeline {
    SearchProbeResponse execute(SearchProbeRequest req);
}