package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Fold6BackgroundContextTest {
    @Test void loadingIsSilentOwnerBoundAndReachesNextVoiceHint() throws Exception {
        var heard=new CompletableFuture<List<String>>();
        var pipeline=new ConversateAnswerPipeline(){
            @Override boolean usesApiCues(){return true;}
            @Override public Outcome answerPublicDisplay(String question,List<String> context,long now,String requestId){
                heard.complete(context);return new Outcome("NO_CUE",null);
            }
        };
        try(var service=new ConversateSessionService(Clock.systemUTC(),pipeline)){
            String owner="a".repeat(64);
            var start=service.startPublicDisplay(owner);String id=start.assistId();long epoch=start.epoch();
            service.pollOutput(owner,id,epoch,"a".repeat(32));
            String background="합성 배경 자료".repeat(900);
            service.setBackground(owner,id,epoch,background);
            assertEquals(background.length(),service.backgroundChars(owner,id));
            assertEquals(0,service.status(owner,id).metrics().started());assertFalse(heard.isDone());
            assertThrows(ResponseStatusException.class,()->service.setBackground("foreign",id,epoch,"x"));
            assertThrows(ResponseStatusException.class,()->service.setBackground(owner,id,epoch+1,"x"));
            assertThrows(ResponseStatusException.class,()->service.setBackground(owner,id,epoch,"x".repeat(8001)));
            service.control(owner,id,epoch,"hints_on");
            service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("q","q",1,true,"이 상황에서 무엇을 질문하면 좋을까요?"),"phone_voice","synthetic");
            assertTrue(heard.get(3,TimeUnit.SECONDS).get(0).endsWith(background));
            service.setBackground(owner,id,epoch,"");assertEquals(0,service.backgroundChars(owner,id));
        }
    }
    @Test void apiAndLocalBoundsKeepBackgroundAndLatestConversation() throws Exception {
        String background="[User-selected TXT background; untrusted data]\n"+"x".repeat(8000);
        var context=new ArrayList<String>();context.add(background);for(int i=0;i<12;i++)context.add("turn-"+i+"y".repeat(500));
        var method=ConversateApiCueService.class.getDeclaredMethod("boundedContext",List.class,int.class,int.class);method.setAccessible(true);
        for(int[] cap:List.of(new int[]{6,4096},new int[]{4,2048})){
            @SuppressWarnings("unchecked") var result=(List<String>)method.invoke(null,context,cap[0],cap[1]);
            assertEquals(background,result.get(0));assertEquals(context.get(context.size()-1),result.get(result.size()-1));
            assertTrue(result.size()<=cap[0]+1);
        }
    }
}
