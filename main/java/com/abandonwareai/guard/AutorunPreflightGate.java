package com.abandonwareai.guard;

import org.springframework.stereotype.Component;

@Component
public class AutorunPreflightGate {
    public boolean allowAction(int citations, int evidences){ return citations>=2 && evidences>=2; }

}