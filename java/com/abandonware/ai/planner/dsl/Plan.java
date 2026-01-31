package com.abandonware.ai.planner.dsl;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class Plan {
    public String name;
    public String version;
    public Map<String, Object> params = new HashMap<>();
    public List<String> chain = new ArrayList<>();
    public static Plan of(String name, String version) {
        Plan p = new Plan();
        p.name = name; p.version = version;
        return p;
    }
}