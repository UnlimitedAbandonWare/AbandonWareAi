package com.example.lms.assist;

import com.example.lms.service.rag.retriever.LocalBm25Retriever;
import com.example.lms.service.rag.LegacyLexicalReranker;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import java.util.*;
import java.util.regex.Pattern;

/** Authorized prepared evidence with optional stateless local summarization; no history or index writes. */
public class ConversateAnswerPipeline {
    private static final SearchDecisionService SEARCH_DECISIONS = new SearchDecisionService();
    private static final Pattern SOURCE_REQUEST = Pattern.compile(
            "(?i)https?://|출처|공식|근거|사실\\s*확인|\\b(?:sources?|citations?|official|evidence)\\b");
    static boolean focusEvidenceRequested(String question){return SOURCE_REQUEST.matcher(question).find();}
    /** Null means that stage did not complete; measured zero remains a real zero. Counts are local prepared-evidence only. */
    public record Stages(Integer indexed,Integer retrieved,Integer eligible,Integer reranked,Integer verified,Long retrievalMs,Long rerankMs,Long verificationMs,Map<String,Object> cue){
        public Stages(Integer indexed,Integer retrieved,Integer eligible,Integer reranked,Integer verified,Long retrievalMs,Long rerankMs,Long verificationMs){this(indexed,retrieved,eligible,reranked,verified,retrievalMs,rerankMs,verificationMs,Map.of());}
        public Stages{cue=cue==null?Map.of():Map.copyOf(cue);}
        static Stages unobserved(){return new Stages(null,null,null,null,null,null,null,null);}
    }
    private static final class Observation {
        Integer indexed,retrieved,eligible,reranked,verified;Long retrievalMs,rerankMs,verificationMs;
        Stages snapshot(){return new Stages(indexed,retrieved,eligible,reranked,verified,retrievalMs,rerankMs,verificationMs);}
    }
    public record Outcome(String reason,ConversateSessionService.Card card,int generationAttempts,int complexJudgments,long generationMs,int searchAttempts,int queryRefinements,Stages stages){
        public Outcome(String reason,ConversateSessionService.Card card,int generationAttempts,int complexJudgments,long generationMs,int searchAttempts,int queryRefinements){this(reason,card,generationAttempts,complexJudgments,generationMs,searchAttempts,queryRefinements,Stages.unobserved());}
        public Outcome(String reason,ConversateSessionService.Card card){this(reason,card,0,0,0,0,0);}
        public Outcome(String reason,ConversateSessionService.Card card,int generationAttempts,int complexJudgments,long generationMs){this(reason,card,generationAttempts,complexJudgments,generationMs,0,0);}
        Outcome searched(int attempts,int refinements){return new Outcome(reason,card,generationAttempts,complexJudgments,generationMs,attempts,refinements,stages);}
        Outcome observed(Stages value){return new Outcome(reason,card,generationAttempts,complexJudgments,generationMs,searchAttempts,queryRefinements,value);}
    }
    private final ConversateLocalCardGenerator generator;
    private com.example.lms.service.ChatService sharedRag;
    private ConversateApiCueService cues;
    void apiCues(ConversateApiCueService cues){this.cues=cues;}
    boolean usesApiCues(){return cues!=null;}
    void sharedRag(com.example.lms.service.ChatService service){this.sharedRag=service;}
    public ConversateAnswerPipeline(){this(null);}
    public ConversateAnswerPipeline(ConversateLocalCardGenerator generator){this.generator=generator;}
    /** Called only inside the existing authenticated Assist worker's admission/cost lease. */
    public Outcome answerLive(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now,String inputPath,String requestId){
        return answerLive(question,context,materials,now,inputPath,requestId,false,false);
    }
    /** Server-selected public entry: no private vector corpus or prepared materials. */
    public Outcome answerPublicDisplay(String question,List<String> context,long now,String requestId){
        return answerLive(question,context,List.of(),now,"glasses_input",requestId,true,false);
    }
    public Outcome answerPublicDisplayDirect(String question,String requestId){
        return cues==null?new Outcome("OPENAI_DIRECT_ERROR",null):cues.answerDirectOpenAi(question,requestId);
    }
    public Outcome answerLive(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now,String inputPath,String requestId,boolean forceHint){
        return answerLive(question,context,materials,now,inputPath,requestId,false,forceHint);
    }
    /** hintTargetChars<=0 keeps the configured default; the per-owner display setting otherwise. */
    public Outcome answerLive(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now,String inputPath,String requestId,boolean forceHint,int hintTargetChars){
        return answerLive(question,context,materials,now,inputPath,requestId,false,forceHint,hintTargetChars);
    }
    private Outcome answerLive(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now,String inputPath,String requestId,boolean publicDisplay,boolean forceHint){
        return answerLive(question,context,materials,now,inputPath,requestId,publicDisplay,forceHint,0);
    }
    private Outcome answerLive(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now,String inputPath,String requestId,boolean publicDisplay,boolean forceHint,int hintTargetChars){
        if(cues!=null)return cues.answer(question,context,materials,publicDisplay,forceHint,hintTargetChars);
        if(ConversateQuestionPolicy.needsHint(question))return suggest(question,context,now);
        if(publicDisplay&&sharedRag==null)return ask("RAG_UNAVAILABLE","답변 서버를 준비하고 있습니다.",now);
        if(sharedRag==null)return "phone_voice".equals(inputPath)&&materials.isEmpty()?ask("RAG_UNAVAILABLE","RAG 연결을 확인해 주세요.",now):answerWithContext(question,context,materials,now);
        if(Thread.currentThread().isInterrupted())return new Outcome("RAG_CANCELLED",null);
        var request=com.example.lms.dto.ChatRequestDto.builder().message(question).inputType("phone_voice".equals(inputPath)?"voice":"text")
                .memoryMode("ephemeral").sessionId(null).understandingEnabled(false).useRag(true)
                .history(context.stream().limit(4).map(text->new com.example.lms.dto.ChatRequestDto.Message("user",text)).toList()).build();
        if(publicDisplay){
            boolean web=SOURCE_REQUEST.matcher(question).find()
                    ||SEARCH_DECISIONS.decide(question,SearchMode.AUTO,null,3,false).shouldSearch();
            request.setUseRag(false);request.setUseWebSearch(web);request.setMaxTokens(192);
            request.setSearchMode(web?SearchMode.AUTO:SearchMode.OFF);
        }
        // Pass authorized volatile context through the canonical prompt assembly's existing context boundary.
        var external=new ArrayList<String>(context);materials.forEach(material->external.add(material.text()));
        var result=sharedRag.continueChat(request,ignored->List.copyOf(external));
        if(Thread.currentThread().isInterrupted())return new Outcome("RAG_CANCELLED",null);
        if(result==null||result.content()==null||result.content().isBlank())return ask("RAG_EMPTY","답변이 비어 있습니다. 다시 확인해 주세요.",System.currentTimeMillis());
        String text=result.content().replace("<!-- rag-control-projection:v1 -->","").strip();
        if(text.codePointCount(0,text.length())>15360)return ask("RAG_TOO_LONG","응답이 표시 한도를 넘었습니다. 질문 범위를 좁혀 주세요.",System.currentTimeMillis());
        var pages=new ArrayList<String>();for(int from=0;from<text.length();){int end=text.offsetByCodePoints(from,Math.min(120,text.codePointCount(from,text.length())));pages.add(text.substring(from,end));from=end;}
        var ids=new ArrayList<String>();var titles=new ArrayList<String>();
        for(var evidence:result.evidenceMetadata()){
            if(ids.size()==4)break;
            if(evidence.marker()==null||!evidence.marker().matches("[A-Za-z0-9_.:\\[\\]-]{1,128}")||evidence.title()==null||evidence.title().isBlank())continue;
            String title=evidence.title().replaceAll("\\p{Cntrl}"," ");if(title.codePointCount(0,title.length())>120)title=title.substring(0,title.offsetByCodePoints(0,119))+"…";
            ids.add(evidence.marker());titles.add(title);
        }
        boolean fallback=result.modelUsed()!=null&&result.modelUsed().toLowerCase(Locale.ROOT).contains("fallback");
        var card=new ConversateSessionService.Card("SHOW",fallback?"FALLBACK":"RAG",pages.get(0),ids,System.currentTimeMillis()+20_000,requestId,titles,pages);
        // Workflow delivery exposes no provider attempt counts or per-stage timings here.
        return new Outcome(fallback?"RAG_FALLBACK":"RAG_ANSWER",card);
    }
    private record Sentence(String key,String sourceId,String text,String normalized){}
    private static final Set<String> STOP=Set.of("얼마","얼마인가요","언제","어디","무엇","무엇인가요","왜","어떻게","몇","인가요","되나요","하나요","보증하나요","있나요");
    private static final Pattern NUMBER=Pattern.compile("[0-9]+(?:[.,][0-9]+)*");
    private static final Pattern QUANTITY=Pattern.compile("[0-9]+(?:[.,][0-9]+)*\\s*(?:만원|개월|년|원|일|시간|분|초|kg|km|%|개)?");
    public Outcome answerWithContext(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now){
        if(suggestion(question))return suggest(question,context,now);
        if(question.strip().matches("^(그것|그거|그 내용|그 용어|그 뜻|그 의미|그 정의|그 개념|그러면|그럼|이것|이거).*")){
            if(context.isEmpty())return ask("CONTEXT_REQUIRED","어떤 내용인지 다시 말씀해 주세요.",now);
            // Retrieval input only. Final model messages still belong to ConversateCardPrompt.
            String contextual=question+" "+context.get(context.size()-1);
            if(contextual.length()>2048)return ask("CONTEXT_LIMIT","질문의 대상을 짧게 다시 말씀해 주세요.",now);
            return answer(contextual,materials,now);
        }
        return answer(question,materials,now);
    }
    public Outcome answer(String question,List<PreparedMaterialReader.Material> materials,long now){
        if(suggestion(question))return suggest(question,List.of(),now);
        var result=answerFact(question,materials,now);var card=result.card();
        if(card!=null&&card.decision().equals("SHOW")&&question.matches(".*(뜻|의미|개념|정의|용어).*"))return new Outcome(result.reason(),new ConversateSessionService.Card(card.decision(),"CONCEPT",card.text(),card.sourceIds(),card.expiresAt()),result.generationAttempts(),result.complexJudgments(),result.generationMs(),result.searchAttempts(),result.queryRefinements(),result.stages());
        return result;
    }
    private static boolean suggestion(String question){return question.matches(".*(다음 질문|대화 제안|무슨 말을|어떤 말을).*" );}
    private Outcome suggest(String question,List<String> context,long now){
        if(generator==null)return ask("GENERATION_DISABLED","대화 제안을 위한 로컬 모델 설정이 필요합니다.",now);
        var r=generator.suggest(question,context,now);return new Outcome(r.reason(),r.card(),r.attempts(),r.complexJudgments(),r.elapsedMs()).observed(new Stages(null,null,null,null,r.verificationMs()==null?null:0,null,null,r.verificationMs()));
    }
    private Outcome answerFact(String question,List<PreparedMaterialReader.Material> materials,long now){
        var observation=new Observation();return answerFact(question,materials,now,observation).observed(observation.snapshot());
    }
    private Outcome answerFact(String question,List<PreparedMaterialReader.Material> materials,long now,Observation observation){
        if(Thread.currentThread().isInterrupted())return ask("CANCELLED","",now);
        if(materials.isEmpty())return ask("NO_MATERIAL","준비 자료를 선택해 주세요.",now);
        long retrievalBegan=System.nanoTime();String query=normalize(question);
        var index=new LocalBm25Retriever();var sentences=new LinkedHashMap<String,Sentence>();
        for(var material:materials)for(String part:material.text().split("(?<=[.!?。])\\s+|\\R")){
            if(sentences.size()>=256)break;String text=part.strip();if(text.isBlank())continue;
            String key="s"+sentences.size(),normalized=normalize(text);sentences.put(key,new Sentence(key,material.sourceId(),text,normalized));index.add(new LocalBm25Retriever.Doc(key,normalized));
        }
        int attempts=1,refinements=0;var matches=index.topK(query,6);observation.indexed=sentences.size();
        if(matches.isEmpty()){
            String refined=refineSpacing(query,sentences.values());
            if(!refined.equals(query)){
                if(Thread.currentThread().isInterrupted())return ask("CANCELLED","",now).searched(attempts,refinements);
                query=refined;refinements=1;attempts++;matches=index.topK(query,6);
            }
        }
        observation.retrieved=matches.size();observation.retrievalMs=elapsed(retrievalBegan);
        if(matches.isEmpty())return ask("NO_MATCH","자료에서 찾지 못했습니다.\n질문을 조금 더 구체적으로 해 주세요.",now).searched(attempts,refinements);
        long rerankBegan=System.nanoTime();
        var words=new HashSet<>(Arrays.asList(query.split(" ")));words.remove("");
        int bestOverlap=matches.stream().mapToInt(d->overlap(words,d.text)).max().orElse(0);
        var eligible=matches.stream().filter(d->overlap(words,d.text)==bestOverlap).map(d->sentences.get(d.id)).toList();
        var candidates=eligible.stream().map(s->Content.from(TextSegment.from(s.normalized(),Metadata.from("candidateId",s.key())))).toList();
        var ranked=new LegacyLexicalReranker().rerank(query,candidates,1);observation.eligible=candidates.size();observation.reranked=ranked.size();observation.rerankMs=elapsed(rerankBegan);if(ranked.isEmpty())return ask("RERANK_DROPPED","확인할 자료가 더 필요합니다.",now).searched(attempts,refinements);
        long verificationBegan=System.nanoTime();
        var selected=sentences.get(ranked.get(0).textSegment().metadata().getString("candidateId"));
        if(!numbers(selected.text()).containsAll(numbers(question))){observation.verified=0;observation.verificationMs=elapsed(verificationBegan);return ask("NUMBER_MISMATCH","질문의 수치와 자료가 다릅니다.\n적용 조건을 확인해 주세요.",now).searched(attempts,refinements);}
        if(eligible.stream().map(s->quantities(s.text())).distinct().count()>1||eligible.stream().map(s->negative(s.text())).distinct().count()>1){observation.verified=0;observation.verificationMs=elapsed(verificationBegan);return ask("EVIDENCE_CONFLICT","자료의 수치나 조건이 서로 다릅니다.\n적용할 근거를 확인해 주세요.",now).searched(attempts,refinements);}
        if(selected.text().codePointCount(0,selected.text().length())>70||!selected.text().matches(".*[가-힣].*")){
            if(generator!=null){
                var evidence=new ArrayList<ConversateCardPrompt.Evidence>();int chars=0;
                for(var sentence:eligible){if(sentence.text().length()>2048||chars+sentence.text().length()>8192)continue;chars+=sentence.text().length();evidence.add(new ConversateCardPrompt.Evidence(sentence.key(),sentence.sourceId(),sentence.text()));}
                if(!evidence.isEmpty()){
                    boolean complex=evidence.size()>1||question.matches(".*(비교|차이|조건|예외|아니|않).*" );
                    var generated=generator.generate(question,evidence,complex,now);
                    observation.verified=generated.verificationMs()==null?null:generated.card().sourceIds().size();observation.verificationMs=generated.verificationMs();
                    if(!generated.reason().equals("GENERATION_DISABLED"))return new Outcome(generated.reason(),generated.card(),generated.attempts(),generated.complexJudgments(),generated.elapsedMs()).searched(attempts,refinements);
                }
            }
            return ask("CARD_NEEDS_SUMMARY","조건을 생략하지 않고\n짧게 확인할 자료가 필요합니다.",now).searched(attempts,refinements);
        }
        boolean sourceValid=materials.stream().anyMatch(m->m.sourceId().equals(selected.sourceId())&&m.text().contains(selected.text()));observation.verified=sourceValid?1:0;observation.verificationMs=elapsed(verificationBegan);
        if(!sourceValid)return ask("SOURCE_REJECTED","근거를 다시 확인해 주세요.",now).searched(attempts,refinements);
        return new Outcome(refinements==0?"MATCH":"MATCH_REFINED",new ConversateSessionService.Card("SHOW","FACT",selected.text(),List.of(selected.sourceId()),now+20_000)).searched(attempts,refinements);
    }
    /** One spacing-only variant, justified by a unique two-word split in the already authorized material. */
    private static String refineSpacing(String query,Collection<Sentence> sentences){
        var vocabulary=new HashSet<String>();for(var sentence:sentences)vocabulary.addAll(Arrays.asList(sentence.normalized().split(" ")));
        var tokens=query.split(" ");
        for(int token=0;token<tokens.length;token++){
            String joined=tokens[token];if(vocabulary.contains(joined)||!joined.matches("[가-힣]{4,20}"))continue;
            String only=null;int count=0;
            for(int cut=2;cut<=joined.length()-2;cut++)if(vocabulary.contains(joined.substring(0,cut))&&vocabulary.contains(joined.substring(cut))){only=joined.substring(0,cut)+" "+joined.substring(cut);count++;}
            if(count>1)return query;
            if(count==1){tokens[token]=only;return String.join(" ",tokens);}
        }
        return query;
    }
    private static Outcome ask(String reason,String text,long now){return new Outcome(reason,new ConversateSessionService.Card(text.isEmpty()?"HOLD":"ASK","CLARIFICATION",text,List.of(),now+20_000));}
    private static long elapsed(long began){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);}
    private static int overlap(Set<String> query,String text){var tokens=new HashSet<>(Arrays.asList(text.split(" ")));tokens.retainAll(query);return tokens.size();}
    private static Set<String> numbers(String s){var out=new HashSet<String>();var m=NUMBER.matcher(s);while(m.find())out.add(m.group());return out;}
    private static Set<String> quantities(String s){var out=new HashSet<String>();var m=QUANTITY.matcher(s);while(m.find())out.add(m.group().replace(" ",""));return out;}
    private static boolean negative(String s){return s.matches(".*(지 않|하지 않|불가|아니|아닙|제외|없).*" );}
    private static String normalize(String text){return Arrays.stream(text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip().split("\\s+")).map(t->t.length()>2&&!t.endsWith("불가")?t.replaceFirst("(에서는|에서|은|는|을|를|이|가|에|의)$",""):t).filter(t->!STOP.contains(t)).reduce((a,b)->a+" "+b).orElse("");}
}
