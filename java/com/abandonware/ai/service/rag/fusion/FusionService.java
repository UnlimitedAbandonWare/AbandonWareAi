package com.abandonware.ai.service.rag.fusion;

import java.util.List;

public class FusionService {
  public static class ScoredDoc {
    public final String id; public final double score; public final String url;
    public ScoredDoc(String id, double score, String url) { this.id=id; this.score=score; this.url=url; }
  }

  public interface Normalizer { List<ScoredDoc> normalize(List<ScoredDoc> in, double lambda); }
  public interface Canonicalizer { String canonical(String url); }
  public interface RRF { List<ScoredDoc> fuse(List<ScoredDoc> web, List<ScoredDoc> vec); }

  private boolean mpLawEnabled = true;
  private double lambda = 0.15;
  private boolean canonicalize = true;
  private Normalizer normalizer = (in, l) -> in;
  private Canonicalizer canonicalizer = (u) -> u;
  private RRF rrf = (w,v) -> w; // placeholder

  public List<ScoredDoc> fuse(List<ScoredDoc> web, List<ScoredDoc> vec) {
    if (mpLawEnabled) {
      web = normalizer.normalize(web, lambda);
      vec = normalizer.normalize(vec, lambda);
    }
    if (canonicalize && web != null) {
      java.util.ArrayList<ScoredDoc> norm = new java.util.ArrayList<>();
      for (ScoredDoc d : web) norm.add(new ScoredDoc(d.id, d.score, canonicalizer.canonical(d.url)));
      web = norm;
    }
    return rrf.fuse(web, vec);
  }

  public void setMpLawEnabled(boolean b) { mpLawEnabled=b; }
  public void setLambda(double l) { lambda=l; }
  public void setCanonicalize(boolean b) { canonicalize=b; }
  public void setNormalizer(Normalizer n) { normalizer=n; }
  public void setCanonicalizer(Canonicalizer c) { canonicalizer=c; }
  public void setRrf(RRF r) { rrf=r; }
}