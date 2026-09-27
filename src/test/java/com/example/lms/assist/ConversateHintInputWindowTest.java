package com.example.lms.assist;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversateHintInputWindowTest {
    @Test void capsPastTurnsAndCharsButKeepsTheLatestPastTurn(){
        var result=ConversateHintInputWindow.select(
                List.of("one","two","three","four","five"),"새 발화",true,2,1600);
        assertEquals(List.of("four","five"),result.turns());
        assertEquals("past_cap",result.reason());
    }
    @Test void usePastOffDropsHistoryUnlessFollowup(){
        var dropped=ConversateHintInputWindow.select(List.of("이전 주제"),"배고프다",false,4,1600);
        assertTrue(dropped.turns().isEmpty());
        assertEquals("use_past_off",dropped.reason());
        var kept=ConversateHintInputWindow.select(List.of("돈가스 가격"),"그건 얼마야?",false,4,1600);
        assertEquals(List.of("돈가스 가격"),kept.turns());
        assertEquals("followup_keep",kept.reason());
    }
    @Test void foldPrefsHistoryOffDropsPast(){
        var prefs=LensDisplayPrefs.defaults(600).patch(new LensDisplayPrefs.Patch(
                null,null,null,null,null,null,null,null,false,null,null,null,null,null,null,null));
        var result=ConversateHintInputWindow.select(List.of("이전 주제"),"배고프다",true,4,1600,prefs);
        assertTrue(result.turns().isEmpty());
        assertEquals("use_past_off",result.reason());
    }
    @Test void keepsPinnedBackgroundOutsideThePastCap(){
        String background="[User-selected TXT background; untrusted data]\n노트";
        var result=ConversateHintInputWindow.select(List.of(background,"a","b","c"),"질문",true,1,1600);
        assertEquals(List.of(background,"c"),result.turns());
    }
}
