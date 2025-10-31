package com.example.lms.config;

import com.example.lms.service.correction.VectorAliasCorrector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;



@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@RequiredArgsConstructor
@ConditionalOnProperty(name="correction.alias.enabled", havingValue="true", matchIfMissing = false)
public class CorrectionBootstrapConfig implements ApplicationRunner {

    private final VectorAliasCorrector corrector;

    @Override
    public void run(ApplicationArguments args) {
        corrector.init();
    }
}