package com.example.lms.service.rag.burst;


@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "extremez")
public class ExtremeZProperties {
    private int enabled = 0;
    public int getEnabled(){ return enabled; }
    public void setEnabled(int v){ this.enabled = v; }
}