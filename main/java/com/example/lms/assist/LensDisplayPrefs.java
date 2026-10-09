package com.example.lms.assist;

import java.util.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;

/** Developer-tunable Meta Display lens presentation values, resolved per owner.
    Carried to the glasses inside lens/text and relay events; the server echoes the
    applied values in testStatus so a requested value is never silently clamped. */
public record LensDisplayPrefs(int transcriptFontPx,int hintFontPx,int transcriptMaxLines,int hintPageLines,
                               long transcriptTtlMs,long hintTtlMs,long autoPageMs,int hintTargetChars,
                               boolean historyEnabled,long historyWindowMs,int historyMaxChars,int historyMaxTokens,
                               boolean topicResetEnabled,
                               long triggerQuietMs,long cueCooldownMs,long forceAfterMs,AutoVoiceTrigger autoVoiceTrigger){
    public LensDisplayPrefs {if(autoVoiceTrigger==null)autoVoiceTrigger=AutoVoiceTrigger.defaults();}
    public static final int MIN_FONT=20,MAX_FONT=36;
    public static final int MIN_TRANSCRIPT_LINES=1,MAX_TRANSCRIPT_LINES=8;
    public static final int MIN_HINT_PAGE_LINES=4,MAX_HINT_PAGE_LINES=13;
    /** Transcript and hint holds share one independent 1-100 s window each. */
    public static final long MIN_TTL_MS=1_000,MAX_TTL_MS=100_000;
    /** 0 disables automatic page advance; any enabled interval is 1-100 s. */
    public static final long MIN_AUTO_PAGE_MS=1_000,MAX_AUTO_PAGE_MS=100_000;
    public static final int MIN_TARGET_CHARS=240,MAX_TARGET_CHARS=1_100;
    /** Past-context knobs: 0 keeps the server default for that dimension. */
    public static final long MIN_HISTORY_WINDOW_MS=5_000,MAX_HISTORY_WINDOW_MS=300_000;
    public static final int MIN_HISTORY_CHARS=200,MAX_HISTORY_CHARS=8_192;
    public static final int MIN_HISTORY_TOKENS=50,MAX_HISTORY_TOKENS=4_096;
    /** Cue/generation cycle. Factory 2.5 s / 10 s / 180 s; force-after must accept 180 s. */
    public static final long MIN_TRIGGER_QUIET_MS=500,MAX_TRIGGER_QUIET_MS=30_000;
    public static final long MIN_CUE_COOLDOWN_MS=1_000,MAX_CUE_COOLDOWN_MS=120_000;
    public static final long MIN_FORCE_AFTER_MS=1_000,MAX_FORCE_AFTER_MS=600_000;

    public static LensDisplayPrefs defaults(int hintTargetChars){
        // Auto-advance defaults ON: the glasses have no key input, so 5 s paging is
        // the only way a multi-page hint stays reachable; 0 remains a valid opt-out.
        return new LensDisplayPrefs(26,26,4,11,20_000,20_000,5_000,
                Math.max(MIN_TARGET_CHARS,Math.min(MAX_TARGET_CHARS,hintTargetChars)),
                true,0,0,0,true,
                2_500,10_000,180_000,AutoVoiceTrigger.defaults());
    }

    /** Null patch fields keep the current value; out-of-range values fail by field name.
        A preset composes the font/line fields first, then explicit fields still win. */
    public LensDisplayPrefs patch(Patch p){
        if(p==null)return this;
        LensDisplayPrefs base=p.preset()==null||p.preset().isBlank()?this:preset(p.preset());
        return new LensDisplayPrefs(
                pick(p.transcriptFontPx(),base.transcriptFontPx,MIN_FONT,MAX_FONT,"transcriptFontPx"),
                pick(p.hintFontPx(),base.hintFontPx,MIN_FONT,MAX_FONT,"hintFontPx"),
                pick(p.transcriptMaxLines(),base.transcriptMaxLines,MIN_TRANSCRIPT_LINES,MAX_TRANSCRIPT_LINES,"transcriptMaxLines"),
                pick(p.hintPageLines(),base.hintPageLines,MIN_HINT_PAGE_LINES,MAX_HINT_PAGE_LINES,"hintPageLines"),
                pick(p.transcriptTtlMs(),base.transcriptTtlMs,MIN_TTL_MS,MAX_TTL_MS,"transcriptTtlMs"),
                pick(p.hintTtlMs(),base.hintTtlMs,MIN_TTL_MS,MAX_TTL_MS,"hintTtlMs"),
                pickAuto(p.autoPageMs(),base.autoPageMs),
                pick(p.hintTargetChars(),base.hintTargetChars,MIN_TARGET_CHARS,MAX_TARGET_CHARS,"hintTargetChars"),
                pick(p.historyEnabled(),base.historyEnabled),
                pickZeroOr(p.historyWindowMs(),base.historyWindowMs,MIN_HISTORY_WINDOW_MS,MAX_HISTORY_WINDOW_MS,"historyWindowMs"),
                pickZeroOr(p.historyMaxChars(),base.historyMaxChars,MIN_HISTORY_CHARS,MAX_HISTORY_CHARS,"historyMaxChars"),
                pickZeroOr(p.historyMaxTokens(),base.historyMaxTokens,MIN_HISTORY_TOKENS,MAX_HISTORY_TOKENS,"historyMaxTokens"),
                pick(p.topicResetEnabled(),base.topicResetEnabled),
                pick(p.triggerQuietMs(),base.triggerQuietMs,MIN_TRIGGER_QUIET_MS,MAX_TRIGGER_QUIET_MS,"triggerQuietMs"),
                pick(p.cueCooldownMs(),base.cueCooldownMs,MIN_CUE_COOLDOWN_MS,MAX_CUE_COOLDOWN_MS,"cueCooldownMs"),
                pick(p.forceAfterMs(),base.forceAfterMs,MIN_FORCE_AFTER_MS,MAX_FORCE_AFTER_MS,"forceAfterMs"),
                base.autoVoiceTrigger.patch(p.autoVoiceTrigger()));
    }
    /** Write-only selector: presets compose only existing font/line fields — TTLs, cue cycle and history stay. */
    private LensDisplayPrefs preset(String name){
        Preset selected;
        try{selected=Preset.valueOf(name.strip().toUpperCase(Locale.ROOT));}
        catch(RuntimeException bad){throw ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:preset");}
        return switch(selected){
            case DEFAULT->withDisplay(26,26,4,11);
            case READ_EASY->withDisplay(30,30,8,8);
            case DENSE->withDisplay(22,22,8,13);
        };
    }
    private LensDisplayPrefs withDisplay(int transcriptFont,int hintFont,int transcriptLines,int hintLines){
        return new LensDisplayPrefs(transcriptFont,hintFont,transcriptLines,hintLines,
            transcriptTtlMs,hintTtlMs,autoPageMs,hintTargetChars,historyEnabled,historyWindowMs,historyMaxChars,historyMaxTokens,
            topicResetEnabled,triggerQuietMs,cueCooldownMs,forceAfterMs,autoVoiceTrigger);
    }
    public enum Preset { DEFAULT,READ_EASY,DENSE }
    private static int pick(Integer value,int current,int min,int max,String field){
        if(value==null)return current;
        if(value<min||value>max)throw ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:"+field);
        return value;
    }
    private static long pick(Long value,long current,long min,long max,String field){
        if(value==null)return current;
        if(value<min||value>max)throw ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:"+field);
        return value;
    }
    private static boolean pick(Boolean value,boolean current){
        return value==null?current:value;
    }
    private static long pickAuto(Long value,long current){
        if(value==null)return current;
        if(value!=0&&(value<MIN_AUTO_PAGE_MS||value>MAX_AUTO_PAGE_MS))throw ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:autoPageMs");
        return value;
    }
    private static long pickZeroOr(Long value,long current,long min,long max,String field){
        if(value==null)return current;
        if(value!=0&&(value<min||value>max))throw ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:"+field);
        return value;
    }
    private static int pickZeroOr(Integer value,int current,int min,int max,String field){
        if(value==null)return current;
        if(value!=0&&(value<min||value>max))throw ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:"+field);
        return value;
    }
    /** Effective values only; never transcript or hint text. */
    public Map<String,Object> describe(){
        var out=new LinkedHashMap<String,Object>();
        out.put("transcriptFontPx",transcriptFontPx);out.put("hintFontPx",hintFontPx);
        out.put("transcriptMaxLines",transcriptMaxLines);out.put("hintPageLines",hintPageLines);
        out.put("transcriptTtlMs",transcriptTtlMs);
        out.put("hintTtlMs",hintTtlMs);out.put("autoPageMs",autoPageMs);out.put("hintTargetChars",hintTargetChars);
        out.put("historyEnabled",historyEnabled);out.put("historyWindowMs",historyWindowMs);
        out.put("historyMaxChars",historyMaxChars);out.put("historyMaxTokens",historyMaxTokens);
        out.put("topicResetEnabled",topicResetEnabled);
        out.put("triggerQuietMs",triggerQuietMs);out.put("cueCooldownMs",cueCooldownMs);out.put("forceAfterMs",forceAfterMs);
        out.put("autoVoiceTrigger",autoVoiceTrigger.describe());
        return out;
    }
    /** Render projections deliberately exclude the owner's registered phrases. */
    public LensDisplayPrefs renderSafe(){return new LensDisplayPrefs(transcriptFontPx,hintFontPx,transcriptMaxLines,hintPageLines,
        transcriptTtlMs,hintTtlMs,autoPageMs,hintTargetChars,historyEnabled,historyWindowMs,historyMaxChars,historyMaxTokens,
        topicResetEnabled,triggerQuietMs,cueCooldownMs,forceAfterMs,autoVoiceTrigger.withoutPhrases());}
    public record Phrase(String id,String language,String text){@Override public String toString(){return "Phrase[redacted]";}}
    public record AutoVoiceTrigger(boolean modeEnabled,boolean hintsEnabled,List<Phrase> phrases,String language,int hintLines,int hintChars,String preset){
        public AutoVoiceTrigger {phrases=phrases==null?List.of():List.copyOf(phrases);}
        public static AutoVoiceTrigger defaults(){return new AutoVoiceTrigger(false,true,List.of(),"ko",3,200,"general");}
        public static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+"," ").toLowerCase(Locale.ROOT);}
        public AutoVoiceTrigger patch(AutoVoicePatch p){
            if(p==null)return this;
            if(Boolean.TRUE.equals(p.restoreDefaults()))return defaults();
            String lang=p.language()==null?language:p.language();
            String selected=p.preset()==null?preset:p.preset();
            if(!Set.of("ko","en").contains(lang)||!Set.of("general","interview").contains(selected))throw invalid("language_or_preset");
            int lines=pick(p.hintLines(),hintLines,3,9,"autoVoiceTrigger.hintLines"),chars=pick(p.hintChars(),hintChars,200,500,"autoVoiceTrigger.hintChars");
            List<Phrase> list=phrases;
            if(p.phrases()!=null){
                if(p.phrases().size()>64)throw invalid("phrases");
                var unique=new LinkedHashMap<String,Phrase>();var ids=new HashSet<String>();int bytes=0;
                for(var phrase:p.phrases()){
                    if(phrase==null||phrase.text()==null||phrase.id()==null||!phrase.id().matches("[A-Za-z0-9_-]{1,64}")||!Set.of("ko","en").contains(phrase.language()))throw invalid("phrases");
                    if(phrase.text().codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw invalid("phrases");
                    String text=Normalizer.normalize(phrase.text(),Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+"," ");
                    if(text.isBlank()||text.codePointCount(0,text.length())>128)throw invalid("phrases");
                    bytes+=text.getBytes(StandardCharsets.UTF_8).length;if(bytes>8192)throw invalid("phrases");
                    String key=phrase.language()+":"+normalize(text);
                    if(!unique.containsKey(key)){if(!ids.add(phrase.id()))throw invalid("phrase_id");unique.put(key,new Phrase(phrase.id(),phrase.language(),text));}
                }
                list=List.copyOf(unique.values());
            }
            boolean mode=pick(p.modeEnabled(),modeEnabled);
            if(mode&&list.stream().noneMatch(f->f.language().equals(lang)))throw invalid("phrases_required");
            return new AutoVoiceTrigger(mode,pick(p.hintsEnabled(),hintsEnabled),list,lang,lines,chars,selected);
        }
        public Phrase match(String text){String value=normalize(text);Phrase best=null;int length=-1;
            for(var p:phrases){String literal=normalize(p.text());int n=literal.codePointCount(0,literal.length());
                if(p.language().equals(language)&&value.contains(literal)&&n>length){best=p;length=n;}}
            return best;
        }
        public AutoVoiceTrigger withoutPhrases(){return new AutoVoiceTrigger(modeEnabled,hintsEnabled,List.of(),language,hintLines,hintChars,preset);}
        public Map<String,Object> describe(){return Map.of("modeEnabled",modeEnabled,"hintsEnabled",hintsEnabled,"phraseCount",phrases.size(),"language",language,"hintLines",hintLines,"hintChars",hintChars,"preset",preset);}
        @Override public String toString(){return describe().toString();}
        private static org.springframework.web.server.ResponseStatusException invalid(String field){return ConversateSessionService.error(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_lens_settings:autoVoiceTrigger."+field);}
    }
    public record AutoVoicePatch(Boolean modeEnabled,Boolean hintsEnabled,List<Phrase> phrases,String language,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=NovaFocusSettings.StrictInteger.class) Integer hintLines,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=NovaFocusSettings.StrictInteger.class) Integer hintChars,
        String preset,Boolean restoreDefaults){}
    public record Patch(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=NovaFocusSettings.StrictInteger.class) Integer transcriptFontPx,
                        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=NovaFocusSettings.StrictInteger.class) Integer hintFontPx,Integer transcriptMaxLines,Integer hintPageLines,
                        Long transcriptTtlMs,Long hintTtlMs,Long autoPageMs,Integer hintTargetChars,
                        Boolean historyEnabled,Long historyWindowMs,Integer historyMaxChars,Integer historyMaxTokens,
                        Boolean topicResetEnabled,
                        Long triggerQuietMs,Long cueCooldownMs,Long forceAfterMs,String preset,AutoVoicePatch autoVoiceTrigger){
        public Patch(Integer transcriptFontPx,Integer hintFontPx,Integer transcriptMaxLines,Integer hintPageLines,
                     Long transcriptTtlMs,Long hintTtlMs,Long autoPageMs,Integer hintTargetChars,
                     Boolean historyEnabled,Long historyWindowMs,Integer historyMaxChars,Integer historyMaxTokens,
                     Boolean topicResetEnabled,Long triggerQuietMs,Long cueCooldownMs,Long forceAfterMs,String preset){
            this(transcriptFontPx,hintFontPx,transcriptMaxLines,hintPageLines,transcriptTtlMs,hintTtlMs,autoPageMs,hintTargetChars,
                historyEnabled,historyWindowMs,historyMaxChars,historyMaxTokens,topicResetEnabled,triggerQuietMs,cueCooldownMs,forceAfterMs,preset,null);
        }
        /** 16-field form keeps older callers and JSON payloads valid. */
        public Patch(Integer transcriptFontPx,Integer hintFontPx,Integer transcriptMaxLines,Integer hintPageLines,
                     Long transcriptTtlMs,Long hintTtlMs,Long autoPageMs,Integer hintTargetChars,
                     Boolean historyEnabled,Long historyWindowMs,Integer historyMaxChars,Integer historyMaxTokens,
                     Boolean topicResetEnabled,
                     Long triggerQuietMs,Long cueCooldownMs,Long forceAfterMs){
            this(transcriptFontPx,hintFontPx,transcriptMaxLines,hintPageLines,transcriptTtlMs,hintTtlMs,autoPageMs,
                hintTargetChars,historyEnabled,historyWindowMs,historyMaxChars,historyMaxTokens,topicResetEnabled,
                triggerQuietMs,cueCooldownMs,forceAfterMs,null);
        }
    }
}
