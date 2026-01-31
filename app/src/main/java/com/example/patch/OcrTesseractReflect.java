package com.example.patch;

import java.util.ArrayList;
import java.util.List;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.patch.OcrTesseractReflect
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.patch.OcrTesseractReflect
role: config
flags: [sse]
*/
public class OcrTesseractReflect {
    public static class Span {
        public final String text; public final int x1,y1,x2,y2; public final float conf;
        public Span(String t,int a,int b,int c,int d,float cf){text=t;x1=a;y1=b;x2=c;y2=d;conf=cf;}
    }
    public static boolean available() {
        try { Class.forName("net.sourceforge.tess4j.Tesseract"); return true; } catch (Throwable t) { return false; }
    }
    public static List<Span> extract(byte[] imageBytes) {
        // Placeholder: actual implementation should decode image and iterate words via Tess4J if present.
        return new ArrayList<>();
    }
}