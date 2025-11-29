package com.example.lms.probe;

import com.example.lms.probe.dto.*;
import org.springframework.context.annotation.*;

@Configuration
public class ProbeConfig {
    @Bean ProbePipeline probePipeline() {
        return (SearchProbeRequest req) -> {
            SearchProbeResponse resp = new SearchProbeResponse();
            StageSnapshot st1 = new StageSnapshot(); st1.name="retrieval:web";
            st1.params.put("webTopK", req.webTopK); st1.params.put("officialOnly", req.flags.officialSourcesOnly);
            CandidateDTO c1 = new CandidateDTO(); c1.id="w1"; c1.title="웹결과1"; c1.source="web"; c1.score=0.72; c1.rank=1; st1.candidates.add(c1);
c1.id="w1"; c1.title="웹결과1"; c1.source="web"; c1.score=0.72; c1.rank=1; st1.candidates.add(c1);
            CandidateDTO c2 = new CandidateDTO(); c2.id="w2"; c2.title="웹결과2"; c2.source="web"; c2.score=0.61; c2.rank=2; st1.candidates.add(c2);
c2.id="w2"; c2.title="웹결과2"; c2.source="web"; c2.score=0.61; c2.rank=2; st1.candidates.add(c2);
            resp.stages.add(st1);

            StageSnapshot st2 = new StageSnapshot(); st2.name="fusion:rrf";
            st2.params.put("k", req.webTopK); st2.params.put("wWeb", 0.6); st2.params.put("wVector", 0.4);
            CandidateDTO cf = new CandidateDTO(); cf.id="f1"; cf.title="융합결과1"; cf.source="fusion"; cf.score=0.75; cf.rank=1; st2.candidates.add(cf);
cf.id="f1"; cf.title="융합결과1"; cf.source="fusion"; cf.score=0.75; cf.rank=1; st2.candidates.add(cf);
            resp.stages.add(st2);

            StageSnapshot st3 = new StageSnapshot(); st3.name="rerank:cross-encoder";
            CandidateDTO cr = new CandidateDTO(); cr.id="r1"; cr.title="재랭크1"; cr.source="rerank"; cr.score=0.88; cr.rank=1; st3.candidates.add(cr);
cr.id="r1"; cr.title="재랭크1"; cr.source="rerank"; cr.score=0.88; cr.rank=1; st3.candidates.add(cr);
            resp.stages.add(st3);
            resp.finalResults.add(cr);
            return resp;
        };
    }
    @Bean SearchProbeService searchProbeService(ProbePipeline p){ return new DefaultSearchProbeService(p); }
}