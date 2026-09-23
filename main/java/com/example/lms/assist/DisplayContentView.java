package com.example.lms.assist;

import java.util.List;
import java.util.Set;
import static com.example.lms.assist.ConversateSessionService.*;

/** Explicit public content allowlist. Internal snapshots remain server-owned. */
final class DisplayContentView {
    private DisplayContentView() {}
    record TextCard(String kind,String text,List<String> sourceTitles,long expiresAt,String requestId,List<String> detailPages) {}
    record Transcript(String utteranceId,int revision,boolean isFinal,String text,long expiresAt,boolean rolling) {
        Transcript(String utteranceId,int revision,boolean isFinal,String text,long expiresAt){this(utteranceId,revision,isFinal,text,expiresAt,expiresAt==ROLLING_EXPIRY);}
    }
    static TextCard card(Card card,long now) {
        if(card==null||card.expiresAt()<=now||!"SHOW".equals(card.decision())||!Set.of("ANSWER","SUGGESTION","TERM","PERSON","CONCEPT","BIO","FACT","RAG","CUE","RAG_CUE","API_DIRECT").contains(card.kind()))return null;
        return new TextCard(card.kind(),card.text(),card.sourceTitles(),card.expiresAt(),card.requestId(),card.detailPages());
    }
    static Transcript caption(Caption caption,long now) {
        return caption==null||caption.expiresAt()<=now?null:new Transcript(caption.utteranceId(),caption.revision(),caption.isFinal(),caption.text(),caption.expiresAt());
    }
}
