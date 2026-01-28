
package com.example.lms.service.rag.fusion;

import com.example.lms.service.rag.scoring.ScoreCalibrator;
import com.example.lms.service.rag.canon.RerankCanonicalizer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;

@Component
public class RrfFusion {

    @Autowired(required=false) private ScoreCalibrator calibrator;
    @Autowired(required=false) private RerankCanonicalizer canonicalizer;

    private final WeightedRRF rrf = new WeightedRRF();

    public RrfFusion(){
        rrf.setMode(System.getProperty("fusion.mode","RRF"));
        rrf.setP(Double.parseDouble(System.getProperty("fusion.wpm.p","1.0")));
    }

    public Map<String,Double> fuse(Map<String, List<WeightedRRF.Candidate>> channels, Map<String,Double> weights){
        if (calibrator!=null) rrf.setExternalCalibrator(calibrator);
        if (canonicalizer!=null) rrf.setExternalCanonicalizer(canonicalizer);
        return rrf.fuse(channels, 60, weights);
    }
}