package com.abandonwareai.selfask;

import org.springframework.stereotype.Component;

@Component
public class SubQuestionPlanner {
    public String[] branch3(String q){ return new String[]{q+" (BQ)", q+" (ER)", q+" (RC)"}; }

}