package com.example.lms.assist;

import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Both optional consumers share one lifecycle owner. No bean is created for two disabled consumers. */
@Configuration(proxyBeanMethods=false)
public class JevRuntimeConfiguration {
    static final class Needed implements Condition {
        public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata) {
            var env=context.getEnvironment();
            return "true".equalsIgnoreCase(env.getProperty("conversate.enabled","false"))
                    ||"true".equalsIgnoreCase(env.getProperty("demo.jev.choice.enabled","false"));
        }
    }
    @Bean(destroyMethod="close") @Conditional(Needed.class)
    public JevEvaluationRuntime jevEvaluationRuntime(Environment env){return new JevEvaluationRuntime(env);}
    @Bean @ConditionalOnProperty(name="demo.jev.choice.enabled",havingValue="true")
    public JevChoiceAdvisor jevChoiceAdvisor(Environment env,JevEvaluationRuntime runtime){return new JevChoiceAdvisor(env,runtime);}
}
