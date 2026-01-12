package com.abandonware.ai.resilience;

import java.util.*;

public class FlowJoiner {
    public enum Mode { PRCYK, PCY, PRC, RYK }
    public Mode decide(boolean hasR, boolean hasK) {
        if (hasR && hasK) return Mode.PRCYK;
        if (!hasR && hasK) return Mode.PCY;
        if (hasR && !hasK) return Mode.PRC;
        return Mode.RYK;
    }
}