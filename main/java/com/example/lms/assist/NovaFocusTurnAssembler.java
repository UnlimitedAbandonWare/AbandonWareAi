package com.example.lms.assist;

import java.util.*;

/** Already-admitted ASR revisions replace a segment. Quiet never promotes interim text. */
public final class NovaFocusTurnAssembler {
    private record Part(String text,boolean finalized){}
    private final LinkedHashMap<String,Part> parts=new LinkedHashMap<>();
    private long changedAt=-1,startedAt=-1;
    private boolean overLimit;
    public boolean update(String key,String text,boolean finalized,long now){
        if(key==null||text==null)throw new IllegalArgumentException("invalid_nova_input");
        var previous=parts.get(key);var next=new Part(text,finalized);
        if(next.equals(previous))return false;
        if(startedAt<0)startedAt=now;
        if(!parts.containsKey(key)&&parts.size()>=64){overLimit=true;return false;}
        int prospective=text().length()-(previous==null?0:previous.text().length())+text.length()+(previous==null&&!parts.isEmpty()?1:0);
        if(overLimit||prospective>8000||now-startedAt>60000){overLimit=true;return false;}
        parts.put(key,next);changedAt=now;
        return true;
    }
    public String text(){return String.join(" ",parts.values().stream().map(Part::text).filter(s->!s.isBlank()).toList()).strip();}
    public boolean finalReady(long now,int quietMs){return !overLimit&&!parts.isEmpty()&&!text().isBlank()&&parts.values().stream().allMatch(Part::finalized)&&now-changedAt>=quietMs;}
    public boolean hasInput(){return !text().isBlank();}
    public boolean contains(String key){return parts.containsKey(key);}
    public boolean finalized(){return !parts.isEmpty()&&parts.values().stream().allMatch(Part::finalized);}
    public boolean overLimit(){return overLimit;}
    public long changedAt(){return changedAt;}
    public void clear(){parts.clear();changedAt=startedAt=-1;overLimit=false;}
}
