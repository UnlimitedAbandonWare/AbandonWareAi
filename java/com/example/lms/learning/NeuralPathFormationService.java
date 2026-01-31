package com.example.lms.learning;

import com.example.lms.domain.path.ReinforcedPath;
import com.example.lms.repository.ReinforcedPathRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;




/**
 * Service that solidifies navigation paths by forming a lightweight neural
 * representation when the path-conformity score exceeds a configured
 * threshold. Reinforced paths are persisted for future lookups via the
 * provided repository.
 */
@Service
public class NeuralPathFormationService {

    private final ReinforcedPathRepository repository;
    private final double threshold;

    public NeuralPathFormationService(ReinforcedPathRepository repository,
                                      @Value("${path.formation.threshold:0.9}") double threshold) {
        this.repository = repository;
        this.threshold = threshold;
    }

    /**
     * Reinforce the given path if the score meets the threshold. A null or
     * blank path is ignored.
     *
     * @param path  normalised path expression, e.g. "A->B"
     * @param score path-conformity score
     */
    public void maybeFormPath(String path, double score) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (score < threshold) {
            return;
        }
        ReinforcedPath rp = new ReinforcedPath();
        rp.setPath(path);
        rp.setScore(score);
        rp.setReinforcedAt(Instant.now());
        repository.save(rp);
    }
}