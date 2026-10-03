package com.example.lms.llm;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.example.lms.service.ChatConversationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class OllamaNativeChatModelContextBudgetTest {
    ModelSpecSnapshot spec(String provider,String model,String host,Instant at){
        return new ModelSpecSnapshot(provider,model,host,4096,null,List.of(),Map.of(),at);
    }
    @Test void onlyFreshMatchingLocalModelAndEndpointSupplyCapacity(){
        assertEquals(4096,DynamicChatModelFactory.validatedContextCapacity(
                spec("ollama","qwen3:8b","127.0.0.1",Instant.now()),"qwen3:8b","http://127.0.0.1:11434/v1"));
        assertNull(DynamicChatModelFactory.validatedContextCapacity(
                spec("ollama","other","127.0.0.1",Instant.now()),"qwen3:8b","http://127.0.0.1:11434/v1"));
        assertNull(DynamicChatModelFactory.validatedContextCapacity(
                spec("ollama","qwen3:8b","other",Instant.now()),"qwen3:8b","http://127.0.0.1:11434/v1"));
        assertNull(DynamicChatModelFactory.validatedContextCapacity(
                spec("ollama","qwen3:8b","127.0.0.1",Instant.now().minusSeconds(86401)),
                "qwen3:8b","http://127.0.0.1:11434/v1"));
        assertNull(DynamicChatModelFactory.validatedContextCapacity(
                spec("openai","qwen3:8b","127.0.0.1",Instant.now()),"qwen3:8b","http://127.0.0.1:11434/v1"));
        assertNull(DynamicChatModelFactory.validatedContextCapacity(null,"qwen3:8b","http://127.0.0.1:11434/v1"));
    }
    @Test void actualOutputOverrideAndFinalTextBoundReachNumCtx()throws Exception{
        var body=new AtomicReference<String>();var count=new AtomicInteger();
        var server=server(body,count);
        try{
            var model=model(server,4096);
            var messages=List.<dev.langchain4j.data.message.ChatMessage>of(UserMessage.from("synthetic question"));
            var result=model.chat(ChatRequest.builder().messages(messages).maxOutputTokens(17).build());
            assertEquals("OK",result.aiMessage().text());
            var options=new ObjectMapper().readTree(body.get()).path("options");
            assertEquals(17,options.path("num_predict").asInt());
            assertEquals(ChatConversationContext.conservativeInput(messages)+17,options.path("num_ctx").asLong());
            assertEquals(1,count.get());
        }finally{server.stop(0);}
    }
    @Test void unknownCapacityKeepsOldOptionsWithoutUniversalDefault()throws Exception{
        var body=new AtomicReference<String>();var count=new AtomicInteger();var server=server(body,count);
        try{
            model(server,null).chat(UserMessage.from("synthetic"));
            assertFalse(new ObjectMapper().readTree(body.get()).path("options").has("num_ctx"));
            assertEquals(1,count.get());
        }finally{server.stop(0);}
    }
    @Test void overCapacityRejectsBeforeWireWithoutTruncatingCurrentQuestion()throws Exception{
        var body=new AtomicReference<String>();var count=new AtomicInteger();var server=server(body,count);
        try{
            var ex=assertThrows(IllegalArgumentException.class,()->model(server,128).chat(UserMessage.from("x".repeat(100))));
            assertEquals("focus_model_context_limit",ex.getMessage());assertEquals(0,count.get());
        }finally{server.stop(0);}
    }
    OllamaNativeChatModel model(HttpServer s,Integer cap){
        return new OllamaNativeChatModel("http://127.0.0.1:"+s.getAddress().getPort()+"/v1",
                "qwen3:8b",Duration.ofSeconds(2),32,.1,null,1,null,false,
                ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled(),cap);
    }
    HttpServer server(AtomicReference<String> body,AtomicInteger count)throws Exception{
        var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        s.createContext("/api/chat",exchange->{
            count.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });s.start();return s;
    }
}

