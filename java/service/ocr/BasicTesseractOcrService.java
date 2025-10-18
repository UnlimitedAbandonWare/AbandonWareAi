package service.ocr;

/**
 * Minimal OCR service stub with confidence threshold gating.
 */
public class BasicTesseractOcrService {
    private double threshold = 0.65;
    public void setThreshold(double t) { this.threshold = t; }
    public boolean accept(double conf) { return conf >= threshold; }
}