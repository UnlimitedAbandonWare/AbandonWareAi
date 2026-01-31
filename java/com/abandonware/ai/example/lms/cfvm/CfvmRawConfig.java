package com.abandonware.ai.example.lms.cfvm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;



@Configuration
@EnableConfigurationProperties(CfvmRawProperties.class)
public class CfvmRawConfig { }