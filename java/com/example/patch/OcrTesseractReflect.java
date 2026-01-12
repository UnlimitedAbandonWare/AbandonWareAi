package com.example.patch;

import java.util.ArrayList;
import java.util.List;


/** Reflective OCR extractor to avoid hard dependency on Tess4J. */
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