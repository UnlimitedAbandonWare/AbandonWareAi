package com.abandonwareai.extreme;

import org.springframework.stereotype.Component;

@Component
public class ExtremeZSystemHandler {
    public String[] burst(String q, int n){ String[] out=new String[n]; for(int i=0;i<n;i++) out[i]=q+" #"+(i+1); return out; }

}