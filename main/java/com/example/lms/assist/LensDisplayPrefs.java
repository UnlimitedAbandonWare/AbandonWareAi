package com.example.lms.assist;

import java.util.*;

/** Developer-tunable Meta Display lens presentation values, resolved per owner.
    Carried to the glasses inside lens/text and relay events; the server echoes the
    applied values in testStatus so a requested value is never silently clamped. */
public record LensDisplayPrefs(int transcriptFontPx,int hintFontPx,int transcriptMaxLines,int hintPageLines,
                               long transcriptTtlMs,long hintTtlMs,long autoPageMs,int hintTargetChars,
                               boolean historyEnabled,long historyWindowMs,int historyMaxChars,int historyMaxTokens,
                               boolean topicResetEnabled,
                               long triggerQuietMs,long cueCooldownMs,long forceAfterMs){
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
                2_500,10_000,180_000);
    }

    /** Null patch fields keep the current value; out-of-range values fail by field name. */
    public LensDisplayPrefs patch(Patch p){
        if(p==null)return this;
        return new LensDisplayPrefs(
                pick(p.transcriptFontPx(),transcriptFontPx,MIN_FONT,MAX_FONT,"transcriptFontPx"),
                pick(p.hintFontPx(),hintFontPx,MIN_FONT,MAX_FONT,"hintFontPx"),
                pick(p.transcriptMaxLines(),transcriptMaxLines,MIN_TRANSCRIPT_LINES,MAX_TRANSCRIPT_LINES,"transcriptMaxLines"),
                pick(p.hintPageLines(),hintPageLines,MIN_HINT_PAGE_LINES,MAX_HINT_PAGE_LINES,"hintPageLines"),
                pick(p.transcriptTtlMs(),transcriptTtlMs,MIN_TTL_MS,MAX_TTL_MS,"transcriptTtlMs"),
                pick(p.hintTtlMs(),hintTtlMs,MIN_TTL_MS,MAX_TTL_MS,"hintTtlMs"),
                pickAuto(p.autoPageMs(),autoPageMs),
                pick(p.hintTargetChars(),hintTargetChars,MIN_TARGET_CHARS,MAX_TARGET_CHARS,"hintTargetChars"),
                pick(p.historyEnabled(),historyEnabled),
                pickZeroOr(p.historyWindowMs(),historyWindowMs,MIN_HISTORY_WINDOW_MS,MAX_HISTORY_WINDOW_MS,"historyWindowMs"),
                pickZeroOr(p.historyMaxChars(),historyMaxChars,MIN_HISTORY_CHARS,MAX_HISTORY_CHARS,"historyMaxChars"),
                pickZeroOr(p.historyMaxTokens(),historyMaxTokens,MIN_HISTORY_TOKENS,MAX_HISTORY_TOKENS,"historyMaxTokens"),
                pick(p.topicResetEnabled(),topicResetEnabled),
                pick(p.triggerQuietMs(),triggerQuietMs,MIN_TRIGGER_QUIET_MS,MAX_TRIGGER_QUIET_MS,"triggerQuietMs"),
                pick(p.cueCooldownMs(),cueCooldownMs,MIN_CUE_COOLDOWN_MS,MAX_CUE_COOLDOWN_MS,"cueCooldownMs"),
                pick(p.forceAfterMs(),forceAfterMs,MIN_FORCE_AFTER_MS,MAX_FORCE_AFTER_MS,"forceAfterMs"));
    }
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
        return out;
    }
    public record Patch(Integer transcriptFontPx,Integer hintFontPx,Integer transcriptMaxLines,Integer hintPageLines,
                        Long transcriptTtlMs,Long hintTtlMs,Long autoPageMs,Integer hintTargetChars,
                        Boolean historyEnabled,Long historyWindowMs,Integer historyMaxChars,Integer historyMaxTokens,
                        Boolean topicResetEnabled,
                        Long triggerQuietMs,Long cueCooldownMs,Long forceAfterMs){}
}
