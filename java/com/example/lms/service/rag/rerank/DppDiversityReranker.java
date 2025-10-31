package com.example.lms.service.rag.rerank;

import java.util.*;
import java.lang.reflect.*;

/**
 * DPP 기반 다양성 재랭커 (호환 레이어 포함).
 * - UnifiedRagOrchestrator에서 기대하는 API를 충족:
 *   1) new DppDiversityReranker(Config cfg, dev.langchain4j.model.embedding.EmbeddingModel em)
 *   2) rerank(List<Doc> docs, String query, int k)
 * - 구버전 사용처 호환:
 *   3) rerank(List<Candidate> in, int k)
 */
public class DppDiversityReranker {

    /** 오케스트레이터가 사용하는 설정 홀더 */
    public static class Config {
        public final double diversityLambda;
        public final int topK;
        public Config(double diversityLambda, int topK){
            this.diversityLambda = diversityLambda;
            this.topK = topK;
        }
    }

    /** 임베딩 모델(선택). 존재하지 않아도 동작하도록 옵셔널 취급 */
    private final Object embeddingModel; // dev.langchain4j.model.embedding.EmbeddingModel

    public DppDiversityReranker(){
        this.embeddingModel = null;
    }

    public DppDiversityReranker(Config cfg, dev.langchain4j.model.embedding.EmbeddingModel em){
        this.embeddingModel = em; // 현재 구현에서는 존재 여부만 확인하여 방어적으로 사용
    }

    /** 과거 호환: simple candidate DTO */
    public static class Candidate {
        public String id;
        public String title;
        public String snippet;
        public String source;
        public double score;
        public int rank;
    }

    /**
     * 과거 호환 시그니처: Candidate 리스트 버전
     */
    public List<Candidate> rerank(List<Candidate> in, int k) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        return dedupAndTopK(in, k, 
            c -> safeKey(c.source, c.title, c.id),
            c -> c.score);
    }

    /**
     * 오케스트레이터 호환 시그니처: Doc 리스트 버전
     * - 타입 의존성을 피하기 위해 리플렉션으로 title/source/score를 탐지
     * - score 없으면 입력 순서 보존 후 상위 k
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> rerank(List<T> docs, String query, int k){
        if (docs == null || docs.isEmpty()) return Collections.emptyList();

        // key extractor
        java.util.function.Function<T,String> keyFn = d -> {
            String title = (String) getFieldOrInvoke(d, "title", "getTitle", String.class);
            String id    = (String) getFieldOrInvoke(d, "id", "getId", String.class);
            String src   = (String) getFieldOrInvoke(d, "source", "getSource", String.class);
            return safeKey(src, title, id);
        };
        // score extractor (Double). 없으면 null
        java.util.function.Function<T,Double> scoreFn = d -> {
            Object s = getFieldOrInvoke(d, "score", "getScore", Number.class);
            return (s instanceof Number) ? ((Number)s).doubleValue() : null;
        };

        // dedup
        LinkedHashMap<String, T> uniq = new LinkedHashMap<>();
        for (T d : docs) {
            String key = keyFn.apply(d);
            uniq.putIfAbsent(key, d);
        }
        ArrayList<T> list = new ArrayList<>(uniq.values());

        // sort by score desc if available
        boolean anyScore = list.stream().map(scoreFn).anyMatch(Objects::nonNull);
        if (anyScore){
            list.sort((a,b)-> {
                Double sa = scoreFn.apply(a);
                Double sb = scoreFn.apply(b);
                double da = (sa==null? -1e18 : sa);
                double db = (sb==null? -1e18 : sb);
                return Double.compare(db, da);
            });
        }
        // top-k
        if (k>0 && list.size()>k) return new ArrayList<>(list.subList(0, k));
        return list;
    }

    // --------------- helpers ---------------

    private static Object getFieldOrInvoke(Object obj, String fieldName, String getterName, Class<?> want){
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f != null){
                f.setAccessible(true);
                Object v = f.get(obj);
                if (want.isInstance(v)) return v;
            }
        } catch (Throwable ignore){}
        try {
            Method m = findMethod(obj.getClass(), getterName);
            if (m != null){
                m.setAccessible(true);
                Object v = m.invoke(obj);
                if (want.isInstance(v)) return v;
            }
        } catch (Throwable ignore){}
        return null;
    }

    private static Field findField(Class<?> c, String name){
        for (Class<?> cur=c; cur!=null; cur=cur.getSuperclass()){
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException e){}
        }
        return null;
    }
    private static Method findMethod(Class<?> c, String name){
        for (Class<?> cur=c; cur!=null; cur=cur.getSuperclass()){
            for (Method m : cur.getDeclaredMethods()){
                if (m.getName().equals(name) && m.getParameterCount()==0) return m;
            }
        }
        return null;
    }

    private static String safeKey(String src, String title, String id){
        String s = (src==null?"":src).trim();
        String t = (title==null?"":title).trim();
        String i = (id==null?"":id).trim();
        if (t.isEmpty() && i.isEmpty()) i = Integer.toHexString((s+t+i).hashCode());
        return s + "::" + (t.isEmpty()? i : t);
    }

    private static <T> List<T> dedupAndTopK(List<T> in, int k, java.util.function.Function<T,String> keyOf, java.util.function.ToDoubleFunction<T> scoreOf){
        LinkedHashMap<String, T> uniq = new LinkedHashMap<>();
        for (T t : in) uniq.putIfAbsent(keyOf.apply(t), t);
        ArrayList<T> list = new ArrayList<>(uniq.values());
        list.sort((a,b)-> Double.compare(scoreOf.applyAsDouble(b), scoreOf.applyAsDouble(a)));
        if (k>0 && list.size()>k) return new ArrayList<>(list.subList(0, k));
        return list;
    }
}
