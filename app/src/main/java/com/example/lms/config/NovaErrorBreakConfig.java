package com.example.lms.config;

import com.example.lms.cfvm.CfvmRawService;
import com.example.lms.cfvm.NovaErrorBreak;
import com.example.lms.cfvm.NovaErrorBreakImpl;
import com.example.lms.service.rag.handler.NovaErrorBreakGuard;
import com.example.lms.service.rag.auth.DomainWhitelist;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.example.lms.cfvm.NovaErrorBreakProperties.class)
public class NovaErrorBreakConfig {

  @Bean
  @Primary
  public CfvmRawService cfvmRawService() {
    return new CfvmRawService();
  }

  @Bean
  public NovaErrorBreak novaErrorBreak(CfvmRawService cfvm, com.example.lms.cfvm.NovaErrorBreakProperties props) {
    return new NovaErrorBreakImpl(cfvm, props);
  }

  @Bean
  public NovaErrorBreakGuard novaErrorBreakGuard(NovaErrorBreak engine, DomainWhitelist whitelist) {
    return new NovaErrorBreakGuard(engine, whitelist);
  }
}