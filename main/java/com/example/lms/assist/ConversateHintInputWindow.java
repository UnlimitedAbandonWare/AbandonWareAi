package com.example.lms.assist;

import java.util.*;

/** Selects which past turns go into one hint request. Does not wipe stored transcripts. */
final class ConversateHintInputWindow {
    record Result(List<String> turns,String reason,int pastChars){}

    static Result select(List<String> recent,String latest,boolean yamlUsePast,int yamlTurns,int yamlChars,LensDisplayPrefs prefs){
        boolean usePast=prefs==null?yamlUsePast:prefs.historyEnabled();
        int maxTurns=yamlTurns;
        int maxChars=yamlChars;
        if(prefs!=null){
            if(prefs.historyMaxChars()>0)maxChars=prefs.historyMaxChars();
            if(prefs.historyMaxTokens()>0)maxChars=Math.min(maxChars,prefs.historyMaxTokens());
        }
        return select(recent,latest,usePast,maxTurns,maxChars);
    }

    static Result select(List<String> recent,String latest,boolean usePast,int maxTurns,int maxChars){
        List<String> raw=recent==null?List.of():recent;
        String background=null;
        var turns=new ArrayList<String>();
        for(int i=0;i<raw.size();i++){
            String turn=raw.get(i);
            if(turn==null||turn.isBlank())continue;
            if(i==0&&turn.startsWith("[User-selected TXT background; untrusted data]\n")&&turn.length()<=8100){
                background=turn;continue;
            }
            turns.add(turn);
        }
        boolean followup=followup(latest);
        if(!usePast&&!followup){
            var kept=new ArrayList<String>();
            if(background!=null)kept.add(background);
            return new Result(List.copyOf(kept),"use_past_off",0);
        }
        int turnCap=Math.max(1,maxTurns);
        int charCap=Math.max(200,maxChars);
        var selected=new ArrayDeque<String>();
        int chars=0;
        for(int i=turns.size()-1;i>=0;i--){
            String turn=turns.get(i);
            int next=chars+turn.length();
            if(!selected.isEmpty()&&(selected.size()>=turnCap||next>charCap))break;
            selected.addFirst(turn);
            chars+=turn.length();
        }
        var out=new ArrayList<String>();
        if(background!=null)out.add(background);
        out.addAll(selected);
        String reason=followup?"followup_keep":selected.size()<turns.size()?"past_cap":"past_keep";
        return new Result(List.copyOf(out),reason,chars);
    }

    static boolean followup(String latest){
        if(latest==null||latest.isBlank())return false;
        String text=latest.strip();
        return text.matches("(?s)^(?:그럼|그러면|그건|그게|그거|그것|그 내용|그 원리|이거|이것|아니|정정|다시).*")
                ||text.matches(".{1,20}(?:은|는|도)(?:요)?[?？.!。 ]*");
    }
}
