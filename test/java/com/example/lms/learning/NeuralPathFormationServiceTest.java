package com.example.lms.learning;

import com.example.lms.domain.path.ReinforcedPath;
import com.example.lms.repository.ReinforcedPathRepository;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests confirming neural-network formation triggers only when
 * the path-conformity score meets the threshold.
 */
public class NeuralPathFormationServiceTest {

    @Test
    void formsNetworkWhenScoreHighEnough() {
        InMemoryRepo repo = new InMemoryRepo();
        NeuralPathFormationService svc = new NeuralPathFormationService(repo, 0.8);
        svc.maybeFormPath("A->B", 0.9);
        assertEquals(1, repo.saved.size(), "path should be reinforced");
    }

    @Test
    void skipsFormationWhenScoreLow() {
        InMemoryRepo repo = new InMemoryRepo();
        NeuralPathFormationService svc = new NeuralPathFormationService(repo, 0.8);
        svc.maybeFormPath("A->B", 0.7);
        assertTrue(repo.saved.isEmpty(), "path should not be reinforced");
    }

    // simple in-memory repository for testing
    static class InMemoryRepo implements ReinforcedPathRepository {
        final List<ReinforcedPath> saved = new ArrayList<>();
        @Override
        public ReinforcedPath save(ReinforcedPath p) {
            saved.add(p);
            return p;
        }
        @Override
        public Optional<ReinforcedPath> findByPath(String path) {
            return saved.stream().filter(r -> r.getPath().equals(path)).findFirst();
        }
    }
}