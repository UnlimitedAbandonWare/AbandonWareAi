package com.example.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


/** Reflective OCR extractor to avoid hard dependency on Tess4J. */
public class OcrTesseractReflect {
    private static final Logger log = Logger.getLogger(OcrTesseractReflect.class.getName());

    public static class Span {
        public final String text; public final int x1,y1,x2,y2; public final float conf;
        public Span(String t,int a,int b,int c,int d,float cf){text=t;x1=a;y1=b;x2=c;y2=d;conf=cf;}
    }
    public static boolean available() {
        logFailSoft("available.disabled", null);
        return false;
    }
    public static List<Span> extract(byte[] imageBytes) {
        // Placeholder: actual implementation should decode image and iterate words via Tess4J if present.
        return new ArrayList<>();
    }

    private static void logFailSoft(String stage, Throwable t) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = t == null ? "unknown" : t.getClass().getSimpleName();
            log.fine("[AWX][ocr][tesseract-reflect] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
