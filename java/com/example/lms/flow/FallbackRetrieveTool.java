
package com.example.lms.flow;


public class FallbackRetrieveTool {
    public String retrieve(String q) {
        return "fallback:" + q;
    }
}