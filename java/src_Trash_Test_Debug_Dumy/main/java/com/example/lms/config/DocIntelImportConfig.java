package com.example.lms.config;

import com.acme.aicore.adapters.docintel.DocIntelClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(DocIntelClient.class) // com.acme.* 패키지의 컴포넌트를 명시적으로 등록
public class DocIntelImportConfig {}