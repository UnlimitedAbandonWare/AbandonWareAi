# demo-1 patch notes

- Added `com.abandonware.ai.service.ocr.OcrConfidenceFilter` that uses reflection to read confidence from spans.
  This prevents `cannot find symbol: getConfidence()` compilation failures across heterogeneous `OcrSpan` models.
- No other behavior changes; the filter **keeps spans** when confidence is unavailable (fail-soft).
