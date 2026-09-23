package com.example.lms.api;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class SemanticRequestFingerprintTest {
    private String hash(String body)throws IOException{return SemanticRequestFingerprint.body(body.getBytes(StandardCharsets.UTF_8));}
    @Test void normalizesOnlyRepresentationalDifferences()throws Exception{
        assertEquals(hash("{\"message\":\"가\\r\\n둘\",\"x\":false}"),hash("{ \"x\": false,\"message\":\"가\\n둘\" }"));
        assertNotEquals(hash("{\"message\":\"안 됩니다\"}"),hash("{\"message\":\"됩니다\"}"));
        assertNotEquals(hash("{\"message\":\"a  b\"}"),hash("{\"message\":\"a b\"}"));
        assertNotEquals(hash("{\"ids\":[1,2]}"),hash("{\"ids\":[2,1]}"));
        assertNotEquals(hash("{\"useRag\":null}"),hash("{\"useRag\":false}"));
    }
    @Test void rejectsAmbiguousDuplicateKeysTrailingJsonAndDeepStructure(){
        assertThrows(IOException.class,()->hash("{\"message\":\"a\",\"message\":\"b\"}"));
        assertThrows(IOException.class,()->hash("{} {}"));
        assertThrows(IOException.class,()->hash("{\"a\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}"));
    }
    @Test void executionControlsMatterButTransportTraceIdsDoNot(){
        var req=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/chat/stream");
        String initial=SemanticRequestFingerprint.request(req,"/api/chat/stream","a".repeat(64));
        req.addHeader("X-Request-Id","trace-only");assertEquals(initial,SemanticRequestFingerprint.request(req,"/api/chat/stream","a".repeat(64)));
        for(String name:java.util.List.of("X-Chat-Run-Ack-Required","X-Jammini-Mode","X-Guard-Level","X-Budget-Ms","X-AWX-Selection-Replay")){
            req.addHeader(name,"1");assertNotEquals(initial,SemanticRequestFingerprint.request(req,"/api/chat/stream","a".repeat(64)),name);req.removeHeader(name);
        }
    }
}
