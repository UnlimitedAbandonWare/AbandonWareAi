package com.example.lms.assist;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Literal matching over canonical Unicode with an explicit map to original offsets. */
public final class NovaWakeMatcher {
    private NovaWakeMatcher(){}
    public record Match(int start,int end,String question){@Override public String toString(){return "NovaWakeMatch[redacted]";}}
    public static Optional<Match> find(String raw,String wake){
        if(raw==null||wake==null||wake.isBlank())return Optional.empty();
        var normalized=new StringBuilder();var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();
        var iterator=BreakIterator.getCharacterInstance(Locale.ROOT);iterator.setText(raw);
        int from=iterator.first();
        for(int to=iterator.next();to!=BreakIterator.DONE;from=to,to=iterator.next()){
            String part=Normalizer.normalize(raw.substring(from,to),Normalizer.Form.NFD);
            normalized.append(part);for(int i=0;i<part.length();i++){starts.add(from);ends.add(to);}
        }
        String wakeLiteral=Normalizer.normalize(wake.strip(),Normalizer.Form.NFD);
        var matcher=Pattern.compile("(?<![\\p{L}\\p{N}\\p{M}_])"+Pattern.quote(wakeLiteral)+"(?![\\p{L}\\p{N}\\p{M}_])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(normalized);
        if(!matcher.find())return Optional.empty();
        int start=starts.get(matcher.start()),end=ends.get(matcher.end()-1),suffix=end;
        while(suffix<raw.length()){
            int cp=raw.codePointAt(suffix);if(!Character.isWhitespace(cp)&&",:;.!?，：；。！？".indexOf(cp)<0)break;suffix+=Character.charCount(cp);
        }
        return Optional.of(new Match(start,end,raw.substring(suffix).strip()));
    }
}
