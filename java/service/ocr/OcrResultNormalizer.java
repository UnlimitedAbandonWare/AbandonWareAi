package service.ocr;
import java.util.*;
public class OcrResultNormalizer {
  public static class ContextDoc {
    public final String id, title, snippet, source; public final double score; public final int rank;
    public ContextDoc(String id, String title, String snippet, String source, double score, int rank){
      this.id=id; this.title=title; this.snippet=snippet; this.source=source; this.score=score; this.rank=rank;
    }
  }
  public List<ContextDoc> normalize(List<String> spans){
    List<ContextDoc> out = new ArrayList<>();
    int i=0;
    for (String s : spans){
      out.add(new ContextDoc("ocr-"+i, "OCR Span "+i, s, "ocr", 0.5, i+1)); i++;
    }
    return out;
  }
}