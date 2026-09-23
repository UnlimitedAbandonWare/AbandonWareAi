package com.example.lms.assist;

import com.example.lms.config.DeepgramProperties;
import com.example.lms.service.stt.DeepgramSttService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversateDeepgramBindingTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(DeepgramProperties.class)
    @Import({ConversateAsrBridge.class,DeepgramSttService.class,ConversateCloudStt.class,ConversateSttBudget.class})
    static class Config {
        @Bean ConversateSessionService sessions(){return new ConversateSessionService();}
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean com.example.lms.api.PublicChatAdmissionGuard admission(){return org.mockito.Mockito.mock(com.example.lms.api.PublicChatAdmissionGuard.class);}
    }
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("conversate.enabled=true","conversate.asr.enabled=true",
                    "deepgram.api-key=","deepgram.api-key-secondary=","conversate.asr.cloud.enabled=false",
                    "conversate.asr.cloud.ledger=","conversate.asr.python=","conversate.asr.script=","conversate.asr.model=");}
    @Test void selectsExistingDeepgramWithoutLocalPythonPaths() {
        context().withPropertyValues("conversate.asr.provider=deepgram","deepgram.api-key"+"=synthetic",
                "conversate.asr.cloud.enabled=true","conversate.asr.cloud.ledger="+java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"unused-binding-ledger.jsonl").toAbsolutePath()).run(c->{
            assertNull(c.getStartupFailure());assertTrue(c.getBean(ConversateAsrBridge.class).available());
            assertEquals(1,c.getBeansOfType(DeepgramSttService.class).size());
        });
    }
    @Test void cloudWithoutBudgetOptInCannotStartEvenWithKey(){
        context().withPropertyValues("conversate.asr.provider=deepgram","deepgram.api-key"+"=synthetic").run(c->{
            assertNull(c.getStartupFailure());assertFalse(c.getBean(ConversateAsrBridge.class).available());
        });
    }
    @Test void missingKeyUnknownAndLocalDefaultRemainUnavailableWithoutPaths(){
        for(String provider:new String[]{"deepgram","unknown","local"}){
            context().withPropertyValues("conversate.asr.provider="+provider).run(c->{
                assertNull(c.getStartupFailure());assertFalse(c.getBean(ConversateAsrBridge.class).available());
            });
        }
    }
}
