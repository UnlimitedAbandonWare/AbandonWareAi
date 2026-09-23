package com.example.lms.assist;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent replay cases for Devin's hint-context work. Uses the existing
 * unleased window/policy layer only — not ConversateHintContextTest,
 * ConversateSessionService, or Fold assets. Time-window, contextEpoch, and
 * late-adopt fencing stay on the session path Devin already owns
 * (generation / hintEpoch / work.ctxEpoch). This class does not prove glasses.
 */
class ConversateHintContextReplayCasesTest {
    static final String PAIRING="안경 페어링 상태와 블루투스 연결 절차를 확인하는 중입니다.";
    static final String HUNGER="지금 배고픈데 근처에 돈가스 집을 추천해.";
    static final String SETTINGS="개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.";
    static final String ENLARGE="그거 조금 더 크게 해줘";
    static final String BG="[User-selected TXT background; untrusted data]\n고정 배경자료";

    @Test void glassesHungerSettingsIsALexicalTopicShift(){
        assertTrue(ConversateQuestionPolicy.lexicalTopicShift(HUNGER,PAIRING,false));
        assertTrue(ConversateQuestionPolicy.lexicalTopicShift(SETTINGS,HUNGER,false));
        var hunger=ConversateQuestionPolicy.cueDecision(HUNGER,List.of(PAIRING));
        var settings=ConversateQuestionPolicy.cueDecision(SETTINGS,List.of(HUNGER));
        assertTrue(hunger.topicChanged());
        assertTrue(settings.topicChanged());
        var window=ConversateHintInputWindow.select(List.of(PAIRING),HUNGER,true,4,1600);
        assertEquals("past_keep",window.reason());
        assertTrue(window.turns().stream().anyMatch(t->t.contains("안경")));
    }

    @Test void enlargeFollowupKeepsNeededPastEvenWhenUsePastIsOff(){
        String past="돈가스 가게 상호를 화면에 보여 주는 중";
        assertTrue(ConversateHintInputWindow.followup(ENLARGE));
        var kept=ConversateHintInputWindow.select(List.of(past),ENLARGE,false,4,1600);
        assertEquals("followup_keep",kept.reason());
        assertEquals(List.of(past),kept.turns());
        var cue=ConversateQuestionPolicy.cueDecision(ENLARGE,List.of(past));
        assertFalse(cue.topicChanged());
        assertTrue(cue.contextRelevant());
        assertFalse(ConversateQuestionPolicy.lexicalTopicShift(ENLARGE,past,true));
    }

    @Test void hintOutputLengthDoesNotWidenThePastInputCap(){
        var prefs=LensDisplayPrefs.defaults(1100).patch(new LensDisplayPrefs.Patch(
                null,null,null,null,null,20_000L,null,1100,true,null,200,null,null,null,null,null));
        assertEquals(1100,prefs.hintTargetChars());
        assertEquals(20_000L,prefs.hintTtlMs());
        assertEquals(200,prefs.historyMaxChars());
        var result=ConversateHintInputWindow.select(List.of("가".repeat(400),"최근 발화"),"질문",true,4,1600,prefs);
        assertEquals("past_cap",result.reason());
        assertEquals(List.of("최근 발화"),result.turns());
        assertTrue(result.pastChars()<=200);
        assertTrue(result.pastChars()<prefs.hintTargetChars());
    }

    @Test void windowLayerHasNoClockIdleDropBelongsToSession(){
        var stillKept=ConversateHintInputWindow.select(List.of(PAIRING),"배고프다",true,4,1600);
        assertEquals(List.of(PAIRING),stillKept.turns());
        assertEquals("past_keep",stillKept.reason());
    }

    @Test void pinnedBackgroundStaysWhenUsePastIsOffAndIsNotCountedAsPast(){
        var off=ConversateHintInputWindow.select(List.of(BG,PAIRING),"배고프다",false,4,1600);
        assertEquals(List.of(BG),off.turns());
        assertEquals("use_past_off",off.reason());
        assertEquals(0,off.pastChars());
        var capped=ConversateHintInputWindow.select(List.of(BG,"a","b","c"),"질문",true,1,1600);
        assertEquals(List.of(BG,"c"),capped.turns());
    }

    @Test void resetIsCallerFilteredTurnsWindowDoesNotReinjectExcludedPast(){
        var afterReset=ConversateHintInputWindow.select(List.of(),"개발자 설정",true,4,1600);
        assertTrue(afterReset.turns().isEmpty());
        assertEquals(0,afterReset.pastChars());
        var leaked=ConversateHintInputWindow.select(List.of(PAIRING),"개발자 설정",true,4,1600);
        assertTrue(leaked.turns().contains(PAIRING));
    }
}
