package ai.abandonware.nova.orch.aop;

import com.example.lms.nova.burst.QueryBurstExpander;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Nova Overlay: Brave 모드(aggressive plan)에서 검색 쿼리 후보를 QueryBurst로 확장합니다.
 *
 * 연결 포인트:
 * - ChatWorkflow는 웹검색/하이브리드 리트리벌 전 단계에서 SmartQueryPlanner.plan(...)을 호출합니다.
 * - 이 AOP는 plan(...)의 반환값(List<String>)을 가로채, Brave(aggressive)일 때
 * QueryBurstExpander로 추가 쿼리를 생성하고 QueryHygieneFilter로 정리합니다.
 */
@Slf4j
@Aspect
public class BraveQueryBurstAspect {

    private final Environment env;
    private final QueryBurstExpander expander = new QueryBurstExpander();

    public BraveQueryBurstAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.search.SmartQueryPlanner.plan(..))")
    public Object expandQueriesInBraveMode(ProceedingJoinPoint pjp) throws Throwable {
        Object base = pjp.proceed();
        if (!(base instanceof List<?> raw)) {
            return base;
        }

        GuardContext ctx = GuardContextHolder.getOrDefault();
        if (ctx == null || !ctx.isAggressivePlan()) {
            return base;
        }

        List<String> baseQs = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof String s && !s.isBlank())
                baseQs.add(s);
        }

        // Config
        int min = env.getProperty("nova.orch.brave-query-burst.min", Integer.class, 9);
        int max = env.getProperty("nova.orch.brave-query-burst.max", Integer.class, 18);
        int cap = env.getProperty("nova.orch.brave-query-burst.cap", Integer.class, max);
        double sim = env.getProperty("nova.orch.brave-query-burst.sim-threshold", Double.class, 0.80d);

        String seed = seedFromArgsOrFallback(pjp.getArgs(), baseQs);
        List<String> burst = (seed == null || seed.isBlank()) ? List.of() : expander.expand(seed, min, max);

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(baseQs);
        merged.addAll(burst);

        List<String> out = QueryHygieneFilter.sanitize(new ArrayList<>(merged), cap, sim);

        if (out.size() != baseQs.size()) {
            log.debug("[nova][query-burst] brave expand {} -> {} (seed='{}')", baseQs.size(), out.size(),
                    shorten(seed));
        }

        return out;
    }

    private static String seedFromArgsOrFallback(Object[] args, List<String> baseQs) {
        // SmartQueryPlanner.plan(String question, @Nullable String modelDraft, int
        // maxQueries)
        if (args != null && args.length >= 1 && args[0] instanceof String s && !s.isBlank()) {
            return s;
        }
        return baseQs.isEmpty() ? null : baseQs.get(0);
    }

    private static String shorten(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.length() <= 60 ? t : t.substring(0, 57) + "...";
    }
}
