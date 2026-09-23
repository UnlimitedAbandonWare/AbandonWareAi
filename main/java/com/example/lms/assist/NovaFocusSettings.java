package com.example.lms.assist;

/** Focus settings are independent of the ordinary caption/cue clocks. */
public record NovaFocusSettings(boolean enabled,String wakeWord,int utteranceQuietMs,int followupIdleMs,
                                int wakeListenTimeoutMs,Presentation presentation,boolean recallEnabled,boolean rememberFactsEnabled) {
    public NovaFocusSettings(boolean enabled,String wakeWord,int quiet,int idle,int listen,Presentation presentation){
        this(enabled,wakeWord,quiet,idle,listen,presentation,false,false);
    }
    public record Presentation(boolean sequentialTextEnabled,int charIntervalMs,int maxVisibleLines,
                               boolean autoFadeEnabled,int tailHoldMs,int fadeMs) {
        public Presentation {range(charIntervalMs,50,160);range(maxVisibleLines,4,8);range(tailHoldMs,2000,15000);range(fadeMs,200,1000);}
        public static Presentation defaults(){return new Presentation(true,80,6,true,5000,400);}
    }
    public NovaFocusSettings {
        if(wakeWord==null||wakeWord.isBlank()||wakeWord.codePointCount(0,wakeWord.length())>16||wakeWord.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid_nova_settings");
        wakeWord=wakeWord.strip();range(utteranceQuietMs,500,5000);range(followupIdleMs,5000,120000);range(wakeListenTimeoutMs,3000,30000);
        if(presentation==null)presentation=Presentation.defaults();
    }
    public static NovaFocusSettings defaults(){return new NovaFocusSettings(false,"노바",1200,20000,8000,Presentation.defaults());}
    private static void range(int value,int min,int max){if(value<min||value>max)throw new IllegalArgumentException("invalid_nova_settings");}
}
