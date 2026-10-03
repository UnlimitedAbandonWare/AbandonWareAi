package com.example.lms.health;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class GpuAdmissionTargetIsolationTest {
    private static final String PRIMARY="GPU-11111111-1111-1111-1111-111111111111";
    private static final String AUX="GPU-22222222-2222-2222-2222-222222222222";
    MockEnvironment env(){return new MockEnvironment().withProperty("awx.gpu-hardware.telemetry.enabled","true")
            .withProperty("local-llm.cuda-visible-device",PRIMARY)
            .withProperty("local-llm.ollama-host","127.0.0.1:11434");}
    String primary(String used){return "1, NVIDIA GeForce RTX 3090, 24576, "+used+
            ", 10, 45, 80, "+PRIMARY+", 0000:01:00.0, 580.0, 20576";}
    String aux(){return "0, NVIDIA GeForce RTX 3060, 12288, 12000, 90, 50, 70, "+AUX+
            ", 0000:02:00.0, 580.0, 288";}
    Map<String,Object> admission(MockEnvironment env,String csv){
        var s=GpuHardwareDiagnostics.snapshot(env,timeout->new GpuHardwareDiagnostics.CommandResult(0,csv,"",false));
        return GpuHardwareDiagnostics.admissionFromSnapshot(s);
    }
    @Test void pressuredUnselectedGpuDoesNotBlockHealthyPrimary(){
        var a=admission(env(),aux()+"\n"+primary("4000"));
        assertEquals("ok",a.get("status"));assertEquals(true,a.get("rerankAllowed"));
        assertTrue((double)a.get("maxMemoryUsedRatio")<.2);
    }
    @Test void missingUnselectedGpuDoesNotBlockKnownTarget(){
        assertEquals("ok",admission(env(),primary("4000")).get("status"));
    }
    @Test void unmatchedConfiguredUuidIsUnknownInsteadOfInventedCapacity(){
        var a=admission(env(),aux());
        assertEquals("gpu_target_identity_needed",a.get("reason"));
        assertFalse(a.containsKey("maxMemoryUsedRatio"));
    }
    @Test void apiEndpointDoesNotConsumeLocalGpuAdmission(){
        var e=env().withProperty("local-llm.ollama-host","https://provider.example.invalid");
        var a=admission(e,aux()+"\n"+primary("24000"));
        assertEquals("observe_only",a.get("status"));assertEquals(true,a.get("rerankAllowed"));
        assertEquals("non_local_endpoint",a.get("reason"));
    }
    @Test void explicitBlockBudgetIsNotRaisedByLargerWarnThreshold(){
        var e=env().withProperty("awx.gpu-hardware.admission.memory-block-threshold","0.80");
        var a=admission(e,primary("20000"));
        assertEquals(.80,(double)a.get("blockThreshold"),1e-9);
        assertEquals("blocked",a.get("status"));
    }
    @Test void unrelatedPartialDriverFailureDoesNotBlockFreshSelectedRow(){
        var snapshot=GpuHardwareDiagnostics.snapshot(env(),timeout->
                new GpuHardwareDiagnostics.CommandResult(1,primary("4000"),"auxiliary device unavailable",false));
        assertEquals("partial",snapshot.get("status"));
        assertEquals("ok",GpuHardwareDiagnostics.admissionFromSnapshot(snapshot).get("status"));
    }
    @Test void unknownTargetMemoryRemainsUnknown(){
        var a=admission(env(),primary("N/A"));
        assertEquals("gpu_memory_evidence_needed",a.get("reason"));
        assertFalse(a.containsKey("maxMemoryUsedRatio"));
    }
}
