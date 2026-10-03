package com.example.lms.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class LocalLlmProcessManagerMemoryEnvironmentTest {
    @Test void explicitlyConfiguredRuntimeControlsReachOwnedLaunch(){
        var env=new MockEnvironment().withProperty("OLLAMA_NUM_PARALLEL","2")
                .withProperty("OLLAMA_MAX_LOADED_MODELS","1").withProperty("OLLAMA_CONTEXT_LENGTH","4096");
        var manager=new LocalLlmProcessManager(env,null);
        LocalLlmProcessManager.LaunchRequest request=ReflectionTestUtils.invokeMethod(manager,
                "buildLaunchRequest","ollama.exe",null);
        assertEquals("2",request.environment().get("OLLAMA_NUM_PARALLEL"));
        assertEquals("1",request.environment().get("OLLAMA_MAX_LOADED_MODELS"));
        assertEquals("4096",request.environment().get("OLLAMA_CONTEXT_LENGTH"));
    }
    @Test void absentControlsDoNotInstallUniversalDefaults(){
        var manager=new LocalLlmProcessManager(new MockEnvironment(),null);
        LocalLlmProcessManager.LaunchRequest request=ReflectionTestUtils.invokeMethod(manager,
                "buildLaunchRequest","ollama.exe",null);
        assertFalse(request.environment().containsKey("OLLAMA_NUM_PARALLEL"));
        assertFalse(request.environment().containsKey("OLLAMA_MAX_LOADED_MODELS"));
        assertFalse(request.environment().containsKey("OLLAMA_CONTEXT_LENGTH"));
    }
    @Test void invalidRuntimeControlFailsBeforeAnyLaunch(){
        for(String value:new String[]{"0","-1","not-an-integer","2147483648"}){
            var manager=new LocalLlmProcessManager(new MockEnvironment().withProperty("OLLAMA_CONTEXT_LENGTH",value),null);
            var ex=assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(manager,
                    "buildLaunchRequest","ollama.exe",null));
            assertEquals("invalid_ollama_runtime_control:OLLAMA_CONTEXT_LENGTH",ex.getMessage());
        }
    }
}

