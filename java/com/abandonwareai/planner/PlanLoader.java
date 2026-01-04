package com.abandonwareai.planner;

import org.springframework.stereotype.Component;

@Component
public class PlanLoader {
    public Plan load(String planName) { return new Plan(planName); }

    public static class Plan { public final String name; public Plan(String n){this.name=n;} }

}