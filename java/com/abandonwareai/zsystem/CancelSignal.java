package com.abandonwareai.zsystem;

import org.springframework.stereotype.Component;

@Component
public class CancelSignal {
    private volatile boolean cancelled=false; public void cancel(){cancelled=true;} public boolean isCancelled(){return cancelled;}

}