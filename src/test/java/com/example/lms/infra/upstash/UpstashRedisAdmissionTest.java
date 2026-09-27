package com.example.lms.infra.upstash;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class UpstashRedisAdmissionTest {
    @Test void evalUsesOneAtomicCommandAndReportsRedisErrorsWithoutEchoingPayload() throws Exception {
        var captured=new AtomicReference<String>();var reply=new AtomicReference<>("[{\"result\":[1,0]}]");
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/pipeline",exchange->{
            captured.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] response=reply.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var client=new UpstashRedisClient(WebClient.builder());
            ReflectionTestUtils.setField(client,"url","http://127.0.0.1:"+server.getAddress().getPort());
            ReflectionTestUtils.setField(client,"token","fixture-placeholder-token");
            assertEquals(List.of(1L,0L),client.eval("return {1,0}",List.of("key-a","key-b"),List.of("arg-a")).block());
            var body=new ObjectMapper().readTree(captured.get());assertEquals(1,body.size());
            assertEquals("EVAL",body.get(0).get(0).asText());assertEquals("2",body.get(0).get(2).asText());
            assertEquals("arg-a",body.get(0).get(5).asText());
            reply.set("[{\"error\":\"private-server-detail\"}]");
            var error=assertThrows(RuntimeException.class,()->client.eval("return {1,0}",List.of(),List.of()).block());
            assertFalse(error.toString().contains("private-server-detail"));
        }finally{server.stop(0);}
    }
}
