package com.example.lms.assist;

import java.util.*;

/** Focus settings are independent of the ordinary caption/cue clocks. */
public record NovaFocusSettings(boolean enabled,String wakeWord,int utteranceQuietMs,int followupIdleMs,
                                int wakeListenTimeoutMs,Presentation presentation,boolean recallEnabled,boolean rememberFactsEnabled,
                                Snapshot snapshot,AnswerSelection answerSelection,RecentContext recentContext,Memory memory,
                                @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictInteger.class) Integer answerLengthChars,Boolean quickAnswerEnabled,Boolean webSearchEnabled,ReasoningPreset reasoningPreset,
                                String answerInstruction,AnswerPreset answerPreset,String cameraWakeWord,String exitWord) {
    /** Retain the former constructor and old JSON payloads. */
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,AnswerSelection selection,RecentContext recent,Memory memory,Integer length,Boolean quick,Boolean web,ReasoningPreset reasoning,String instruction,AnswerPreset preset,String camera){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,selection,recent,memory,length,quick,web,reasoning,instruction,preset,camera,null);
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,AnswerSelection selection,RecentContext recent,Memory memory,Integer length,Boolean quick,Boolean web){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,selection,recent,memory,length,quick,web,null,null,null,null);
    }
    public enum ReasoningPreset {
        FAST("low"),STANDARD("medium"),DEEP("high");
        private final String effort;
        ReasoningPreset(String effort){this.effort=effort;}
        public String effort(){return effort;}
    }
    /** 답변 지침 프리셋. null과 GENERAL은 지침 없음(기존 동작); 입력칸을 고치면 CUSTOM. */
    public enum AnswerPreset {GENERAL,INTERVIEW,CUSTOM}
    /** INTERVIEW 프리셋 기본 지침 — 서버 상수 한 곳에만 둔다. */
    public static final String INTERVIEW_ANSWER_INSTRUCTION="너는 면접 중인 사용자를 돕는 안경 보조다. 들린 면접 질문에 대해 사용자가 그대로 말할 수 있는 1인칭 답변을 짧은 문장 2~4개로 준다. 존댓말, 핵심 먼저, 숫자·경험 예시 1개. 확실하지 않은 사실은 지어내지 않는다.";
    public static final int ANSWER_INSTRUCTION_MAX_CHARS=1200;
    public ReasoningPreset effectiveReasoningPreset(){return reasoningPreset==null?ReasoningPreset.STANDARD:reasoningPreset;}
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,AnswerSelection selection,RecentContext recent,Memory memory,Integer length,Boolean quick){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,selection,recent,memory,length,quick,null);
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,AnswerSelection selection,RecentContext recent,Memory memory){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,selection,recent,memory,null,null);
    }
    /** Local numeric fields reject Jackson's default float/string-to-integer coercion. */
    public static final class StrictInteger extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser p,com.fasterxml.jackson.databind.DeserializationContext c) throws java.io.IOException {
            if(!p.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)||!p.getNumberValue().toString().matches("-?[0-9]{1,10}"))
                throw com.fasterxml.jackson.databind.JsonMappingException.from(p,"invalid_display_integer");
            try{return Integer.valueOf(p.getNumberValue().toString());}
            catch(NumberFormatException e){throw com.fasterxml.jackson.databind.JsonMappingException.from(p,"invalid_display_integer");}
        }
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,AnswerSelection answerSelection,RecentContext recentContext){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,answerSelection,recentContext,null);
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,AnswerSelection answerSelection){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,answerSelection,null);
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,(AnswerSelection)null);
    }
    /** 사진 호출어만 따로 지정하는 경로 — 나머지 선택 블록은 null(기본값) 위임. */
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember,Snapshot snapshot,String cameraWakeWord){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,snapshot,null,null,null,null,null,null,null,null,null,cameraWakeWord);
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation){
        this(enabled,wakeWord,quiet,idle,listen,presentation,false,false,null);
    }
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation,boolean recall,boolean remember){
        this(enabled,wakeWord,quiet,idle,listen,presentation,recall,remember,null);
    }
    public record Presentation(boolean sequentialTextEnabled,int charIntervalMs,int maxVisibleLines,
                               boolean autoFadeEnabled,int tailHoldMs,int fadeMs) {
        public Presentation {range(charIntervalMs,50,160);range(maxVisibleLines,4,8);range(tailHoldMs,2000,15000);range(fadeMs,200,1000);}
        public static Presentation defaults(){return new Presentation(true,80,6,true,5000,400);}
    }
    /** 확정된 질문 한 건당 선택한 장치로 사진 한 장. null은 구버전 페이로드(필드 없음)를 뜻한다. */
    public record Snapshot(boolean enabled,String source,Boolean cameraAllowed) {
        public Snapshot(boolean enabled,String source){this(enabled,source,null);}
        public Snapshot {if(!Set.of("FOLD_REAR","META_GLASSES").contains(source))throw new IllegalArgumentException("invalid_nova_settings");}
        /** 카메라 전체 허용 스위치 — false면 어떤 호출어로도 촬영하지 않는다. null=구버전 페이로드(허용). */
        public boolean cameraAllowedOrDefault(){return cameraAllowed==null||cameraAllowed;}
        public static Snapshot defaults(){return new Snapshot(false,"FOLD_REAR",null);}
    }
    /** Omitted blocks mean legacy payloads; FIXED reuses the existing exact-model request gate. */
    public record AnswerSelection(Mode mode,String modelId,Routing routing) {
        public AnswerSelection(Mode mode,String modelId){this(mode,modelId,null);}
        public enum Mode { AUTO,FIXED }
        public AnswerSelection {
            if(mode==null)throw new IllegalArgumentException("invalid_nova_settings");
            if(mode==Mode.AUTO)modelId=null;
            else if(!validModelId(modelId))
                throw new IllegalArgumentException("invalid_nova_settings");
        }
        public static AnswerSelection defaults(){return new AnswerSelection(Mode.AUTO,null);}
    }
    public enum ExecutionTarget { AUTO,API_ONLY,LOCAL_ONLY,GEMINI_WEBSEARCH_ONLY }
    /** RAM-only final input; bounds are rejected, never silently clamped. */
    public record RecentContext(boolean enabled,int maxAgeSeconds,int maxUtterances,int tokenBudget) {
        public RecentContext {range(maxAgeSeconds,30,180);range(maxUtterances,1,12);range(tokenBudget,256,2000);}
        public static RecentContext defaults(){return new RecentContext(true,180,12,2000);}
    }
    /** Optional for old clients; an explicit block constrains every primary/fallback attempt. */
    public record Routing(ExecutionTarget executionTarget,boolean fallbackAllowed,List<String> allowedFallbackIds) {
        /** Stored general-mode preference survives strict mode; this is the execution policy. */
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean effectiveFallbackAllowed(){return fallbackAllowed&&executionTarget!=ExecutionTarget.GEMINI_WEBSEARCH_ONLY;}
        public Routing {
            if(executionTarget==null)throw new IllegalArgumentException("invalid_nova_settings");
            allowedFallbackIds=allowedFallbackIds==null?List.of():List.copyOf(allowedFallbackIds);
            if(allowedFallbackIds.size()>3||allowedFallbackIds.stream().anyMatch(id->!validModelId(id)))
                throw new IllegalArgumentException("invalid_nova_settings");
            allowedFallbackIds=List.copyOf(new LinkedHashSet<>(allowedFallbackIds));
        }
    }
    /** Opt-in Focus memory/graph policy. A null mode keeps the legacy recall/remember switches;
        OFF|RECALL|FULL override them. graphMode gates only the private co-mention expansion —
        it is opt-in and OFF by default. webOnUnknown null inherits the server policy. */
    public record Memory(Mode mode,GraphMode graphMode,Integer maxEvidence,EmbeddingPrefer embeddingPrefer,Boolean webOnUnknown) {
        public Memory {
            if(graphMode==null)graphMode=GraphMode.OFF;
            if(maxEvidence==null)maxEvidence=4;
            range(maxEvidence,1,8);
            if(embeddingPrefer==null)embeddingPrefer=EmbeddingPrefer.LOCAL_THEN_CLOUD;
        }
        public enum Mode { OFF,RECALL,FULL }
        public enum GraphMode { OFF,AUTO,ON }
        public enum EmbeddingPrefer { LOCAL_ONLY,LOCAL_THEN_CLOUD,CLOUD_ONLY }
        public static Memory defaults(){return new Memory(null,GraphMode.OFF,4,EmbeddingPrefer.LOCAL_THEN_CLOUD,null);}
    }
    private static boolean validModelId(String id){return id!=null&&id.length()<=180&&id.matches("[a-zA-Z0-9][a-zA-Z0-9._/:+-]*");}
    public NovaFocusSettings {
        if(wakeWord==null||wakeWord.isBlank()||wakeWord.codePointCount(0,wakeWord.length())>16||wakeWord.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid_nova_settings");
        wakeWord=wakeWord.strip();range(utteranceQuietMs,500,5000);range(followupIdleMs,5000,120000);range(wakeListenTimeoutMs,3000,30000);
        // 사진 호출어: null이면 기본값 "데빈", 빈 문자열이면 기능 끔. 규칙은 wakeWord와 같고 같은 단어는 불가.
        if(cameraWakeWord!=null){cameraWakeWord=cameraWakeWord.strip();
            if(!cameraWakeWord.isEmpty()){if(cameraWakeWord.codePointCount(0,cameraWakeWord.length())>16||cameraWakeWord.codePoints().anyMatch(Character::isISOControl)||cameraWakeWord.equals(wakeWord))throw new IllegalArgumentException("invalid_nova_settings");}}
        if(presentation==null)presentation=Presentation.defaults();
        if(exitWord!=null){exitWord=exitWord.strip();
            if(exitWord.isBlank()||exitWord.codePointCount(0,exitWord.length())>16||exitWord.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid_nova_settings");}
        String effectiveExit=exitWord==null?"\uD074\uB9B0":exitWord;
        if(sameCommand(effectiveExit,wakeWord)||sameCommand(effectiveExit,cameraWakeWord==null?"\uB370\uBE48":cameraWakeWord))throw new IllegalArgumentException("invalid_nova_settings");
        if(answerLengthChars!=null)range(answerLengthChars,80,800);
        answerInstruction=normalizeInstruction(answerInstruction);
    }
    public int effectiveAnswerLengthChars(){return answerLengthChars==null?400:answerLengthChars;}
    public String exitWordOrDefault(){return exitWord==null?"\uD074\uB9B0":exitWord;}
    static boolean sameCommand(String left,String right){return java.text.Normalizer.normalize(left.strip(),java.text.Normalizer.Form.NFC).equalsIgnoreCase(java.text.Normalizer.normalize(right.strip(),java.text.Normalizer.Form.NFC));}
    public AnswerPreset effectiveAnswerPreset(){return answerPreset==null?AnswerPreset.GENERAL:answerPreset;}
    /** 비어 있으면 프리셋 기본 문구가 적용되고 GENERAL은 빈 문자열로 기존 동작을 유지한다. */
    public String effectiveAnswerInstruction(){return answerInstruction!=null&&!answerInstruction.isBlank()?answerInstruction:effectiveAnswerPreset()==AnswerPreset.INTERVIEW?INTERVIEW_ANSWER_INSTRUCTION:"";}
    /** 줄바꿈·탭만 허용하는 자유 텍스트; 공백만 남으면 "" 로 정규화해 명시적 비우기를 구분한다. */
    private static String normalizeInstruction(String value){
        if(value==null)return null;
        String text=value.replace("\r\n","\n").replace('\r','\n').strip();
        if(text.codePointCount(0,text.length())>ANSWER_INSTRUCTION_MAX_CHARS||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new IllegalArgumentException("invalid_nova_settings");
        return text;
    }
    public boolean quickAnswer(){return Boolean.TRUE.equals(quickAnswerEnabled);}
    /** null retains the existing intent-based policy; false dominates every search decision. */
    public boolean webSearchAllowed(){return !Boolean.FALSE.equals(webSearchEnabled);}
    public Presentation effectivePresentation(){var p=presentation;return !quickAnswer()?p:new Presentation(false,p.charIntervalMs(),p.maxVisibleLines(),p.autoFadeEnabled(),p.tailHoldMs(),p.fadeMs());}
    /** 사진 호출어: null=기본 "데빈", ""=기능 끔. */
    public String cameraWakeWordOrDefault(){return cameraWakeWord==null?"데빈":cameraWakeWord;}
    public Snapshot snapshotOrDefault(){return snapshot==null?Snapshot.defaults():snapshot;}
    public AnswerSelection answerSelectionOrDefault(){return answerSelection==null?AnswerSelection.defaults():answerSelection;}
    public RecentContext recentContextOrDefault(){return recentContext==null?RecentContext.defaults():recentContext;}
    public Memory memoryOrDefault(){return memory==null?Memory.defaults():memory;}
    /** An explicit memory mode overrides the legacy switches; without one the booleans stay authoritative. */
    public boolean effectiveRecallEnabled(){var mode=memoryOrDefault().mode();return mode==null?recallEnabled:mode!=Memory.Mode.OFF;}
    public boolean effectiveRememberFactsEnabled(){var mode=memoryOrDefault().mode();return mode==null?rememberFactsEnabled:mode==Memory.Mode.FULL;}
    public static NovaFocusSettings defaults(){return new NovaFocusSettings(false,"노바",1200,20000,12000,Presentation.defaults(),false,false,Snapshot.defaults(),AnswerSelection.defaults(),RecentContext.defaults(),Memory.defaults(),400,false);}
    private static void range(int value,int min,int max){if(value<min||value>max)throw new IllegalArgumentException("invalid_nova_settings");}
}
