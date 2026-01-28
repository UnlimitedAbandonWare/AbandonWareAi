package com.example.lms.config;

import com.example.lms.transform.QueryTransformerCustomDict;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Non-LLM query alias dictionary for {@link com.example.lms.transform.QueryTransformer}.
 *
 * <p>This replaces the previous {@code DefaultQueryTransformer} "alias" behaviour without
 * introducing an additional {@code QueryTransformer} bean (which could break DI).
 */
@Configuration
public class QueryTransformerDictConfig {

    // MERGE_HOOK:PROJ_AGENT::QUERYTRANSFORMER_CUSTOMDICT_BEAN_WRAP_V1
    @Bean(name = "queryTransformerCustomDict")
    public QueryTransformerCustomDict queryTransformerCustomDict() {
        // Keep this map small & stable for cache hits.
        return new QueryTransformerCustomDict(Map.of(
                // Galaxy Fold
                "폴드7", "갤럭시 Z 폴드 7",
                "fold7", "Galaxy Z Fold 7",
                "Galaxy Fold 7", "Galaxy Z Fold 7",
                "Galaxy Z Fold7", "Galaxy Z Fold 7",

                "폴드6", "갤럭시 Z 폴드 6",
                "fold6", "Galaxy Z Fold 6",
                "Galaxy Fold 6", "Galaxy Z Fold 6",
                "Galaxy Z Fold6", "Galaxy Z Fold 6"
        ));
    }
}

