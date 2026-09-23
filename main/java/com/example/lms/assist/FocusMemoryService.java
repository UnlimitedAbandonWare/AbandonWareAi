package com.example.lms.assist;

import com.example.lms.service.ChatConversationContext;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.embedding.OllamaEmbeddingModel;
import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.vector.EmbeddingFingerprint;
import com.abandonware.ai.addons.budget.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** One opt-in, owner-scoped memory lane. It never indexes a Focus conversation automatically. */
@Service
public class FocusMemoryService {
    public enum Status { OFF, NO_AUTHORIZED_MEMORY, OK, DEGRADED, BLOCKED_SCOPE, TIMED_OUT, CANCELLED }
    public record Result(List<MemoryEvidence> evidence,Status status,String retrievalMode,int vectorHits,int graphHits,
                         int graphHops,int evidenceBytes,long tookMs,boolean truncated,String degradationReason) {
        static Result empty(Status status,String reason){return new Result(List.of(),status,"NONE",0,0,0,2,0,false,reason);}
        @Override public String toString(){return "FocusMemoryResult[status="+status+",evidenceCount="+evidence.size()+"]";}
    }
    public record Fact(String sourceId,long revision,String text,List<String> entities,String assertionType,Instant eventTime,
                       Instant recordedAt,boolean indexed) {
        @Override public String toString(){return "FocusMemoryFactView[redacted]";}
    }
    /** Body has no owner and cannot promote content to VERIFIED. */
    public record Edit(String sourceId,long expectedRevision,long consentRevision,String text,List<String> entities,
                       String assertionType,Instant eventTime,boolean confirmed) {
        @Override public String toString(){return "FocusMemoryEdit[redacted]";}
    }
    @PersistenceContext private EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final OllamaEmbeddingModel embeddings;
    private final EmbeddingFingerprint fingerprint;
    public FocusMemoryService(PlatformTransactionManager manager,OllamaEmbeddingModel embeddings,EmbeddingFingerprint fingerprint){
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(3);this.embeddings=embeddings;this.fingerprint=fingerprint;
    }
    private <T>T transaction(Supplier<T> action){return tx.execute(status->action.get());}
    private NovaFocusSettings settings(NovaFocusProfile p){
        try{return p==null||p.getSettingsJson()==null?NovaFocusSettings.defaults():mapper.readValue(p.getSettingsJson(),NovaFocusSettings.class);}
        catch(Exception bad){throw new IllegalStateException("focus_settings_unreadable");}
    }
    public FocusMemoryScope scope(String owner,String channel){
        String id=NovaFocusHistoryService.scope(owner,channel);
        return transaction(()->{var p=em.find(NovaFocusProfile.class,id);
            return new FocusMemoryScope(id,p==null?0:p.getSettingsVersion(),p==null?0:p.getMemoryRevision(),1,settings(p).recallEnabled());});
    }
    private boolean matches(FocusMemoryScope scope,NovaFocusProfile p){
        return p!=null&&p.getSettingsVersion()==scope.consentRevision()&&p.getMemoryRevision()==scope.indexRevision()
            &&settings(p).recallEnabled()==scope.recallEnabled();
    }
    public boolean current(FocusMemoryScope scope){
        return scope==null||transaction(()->matches(scope,em.find(NovaFocusProfile.class,scope.namespace())));
    }
    private List<FocusMemoryFact> rows(String scope){
        return em.createQuery("select f from FocusMemoryFact f where f.scopeId=:s and f.currentRevision=true and f.deletedAt is null and f.validTo is null order by f.recordedAt desc",FocusMemoryFact.class)
            .setParameter("s",scope).setMaxResults(257).getResultList();
    }
    private List<String> entities(FocusMemoryFact f){
        try{return List.of(mapper.readValue(f.getEntitiesJson(),String[].class));}
        catch(Exception invalid){throw new IllegalStateException("focus_memory_index_invalid");}
    }
    private Fact view(FocusMemoryFact f){return new Fact(f.getSourceId(),f.getRevision(),f.getText(),entities(f),f.getAssertionType(),f.getEventTime(),f.getRecordedAt(),f.getEmbedding()!=null);}
    public List<Fact> list(String owner,String channel){
        return transaction(()->rows(NovaFocusHistoryService.scope(owner,channel)).stream().map(this::view).toList());
    }
    private NovaFocusProfile writable(String scope,long consent,boolean remember){
        var p=em.find(NovaFocusProfile.class,scope,LockModeType.PESSIMISTIC_WRITE);
        if(p==null||p.getSettingsVersion()!=consent)throw new IllegalArgumentException("focus_memory_consent_conflict");
        if(remember&&!settings(p).rememberFactsEnabled())throw new IllegalArgumentException("focus_memory_save_disabled");
        return p;
    }
    private static String sourceId(String id){
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("invalid_memory_source");return id;
    }
    private FocusMemoryFact latest(String scope,String source){
        var found=em.createQuery("select f from FocusMemoryFact f where f.scopeId=:s and f.sourceId=:id and f.currentRevision=true",FocusMemoryFact.class)
            .setParameter("s",scope).setParameter("id",sourceId(source)).setMaxResults(1).getResultList();
        if(found.isEmpty())throw new IllegalArgumentException("focus_memory_source_conflict");return found.get(0);
    }
    public Fact save(String owner,String channel,Edit edit){
        String id=NovaFocusHistoryService.scope(owner,channel);
        if(edit==null||!edit.confirmed()||edit.text()==null||edit.text().isBlank()||edit.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1200
            ||edit.entities()==null||edit.entities().size()>8
            ||!Set.of("USER_REPORTED","HYPOTHESIS","ASSISTANT_GENERATED").contains(Objects.toString(edit.assertionType(),"")))
            throw new IllegalArgumentException("invalid_memory_confirmation");
        var terms=new LinkedHashSet<String>();
        for(String term:edit.entities()){
            if(term==null||term.isBlank()||term.length()>48||term.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid_memory_entity");
            terms.add(term.strip().toLowerCase(Locale.ROOT));
        }
        transaction(()->{writable(id,edit.consentRevision(),true);return null;});
        byte[] vector=null;String fp=null;
        var previous=TimeBudgetContext.get();long allowed=ChatRunExecutionContext.capRequestWait(800);
        TimeBudgetContext.set(new TimeBudget(allowed));
        try{vector=encode(embed(edit.text()));fp=fingerprint.fingerprint();}
        catch(CancellationException cancelled){throw cancelled;}
        catch(RuntimeException unavailable){/* Fact remains authoritative; lexical/local-graph fallback is explicit. */}
        finally{if(previous==null)TimeBudgetContext.clear();else TimeBudgetContext.set(previous);}
        final byte[] projection=vector;final String identity=fp;
        return transaction(()->{
            var p=writable(id,edit.consentRevision(),true);
            var all=rows(id);if(edit.sourceId()==null&&all.size()>=256)throw new IllegalStateException("focus_memory_capacity");
            FocusMemoryFact old=edit.sourceId()==null?null:latest(id,edit.sourceId());
            if(old==null&&edit.expectedRevision()!=0||old!=null&&(old.getRevision()!=edit.expectedRevision()||old.getDeletedAt()!=null))
                throw new IllegalArgumentException("focus_memory_revision_conflict");
            Instant now=Instant.now();var f=new FocusMemoryFact();
            f.setSourceId(old==null?UUID.randomUUID().toString():old.getSourceId());f.setRevision(old==null?1:old.getRevision()+1);
            f.setId(f.getSourceId()+":"+f.getRevision());f.setScopeId(id);f.setCurrentRevision(true);f.setConsentRevision(edit.consentRevision());
            if(old!=null){old.setCurrentRevision(false);old.setValidTo(now);f.setSupersedesId(old.getId());}
            f.setText(edit.text().strip());f.setAssertionType(edit.assertionType());f.setEventTime(edit.eventTime()==null?now:edit.eventTime());f.setRecordedAt(now);
            try{f.setEntitiesJson(mapper.writeValueAsString(terms));}catch(Exception impossible){throw new IllegalArgumentException("invalid_memory_entity");}
            f.setEmbedding(projection);f.setEmbeddingFingerprint(identity);em.persist(f);p.setMemoryRevision(p.getMemoryRevision()+1);
            return view(f);
        });
    }
    public void delete(String owner,String channel,String source,long revision,long consent){
        String id=NovaFocusHistoryService.scope(owner,channel);
        transaction(()->{var p=writable(id,consent,false);var latest=latest(id,source);
            if(latest.getRevision()!=revision)throw new IllegalArgumentException("focus_memory_revision_conflict");
            if(latest.getDeletedAt()!=null)return null;
            var versions=em.createQuery("select f from FocusMemoryFact f where f.scopeId=:s and f.sourceId=:id",FocusMemoryFact.class).setParameter("s",id).setParameter("id",source).getResultList();
            Instant now=Instant.now();for(var f:versions){f.setDeletedAt(now);f.setValidTo(now);f.setText(null);f.setEntitiesJson("[]");f.setEmbedding(null);f.setEmbeddingFingerprint(null);}
            p.setMemoryRevision(p.getMemoryRevision()+1);return null;});
    }
    private float[] embed(String text){
        if(!"ollama".equals(fingerprint.provider()))throw new IllegalStateException("private_embedding_provider_disabled");
        float[] vector=embeddings.embedPrivate(text).vector();
        if(vector.length!=fingerprint.dimensions())throw new IllegalStateException("private_embedding_dimension_mismatch");
        double norm=0;for(float v:vector){if(!Float.isFinite(v))throw new IllegalStateException("private_embedding_invalid");norm+=v*v;}
        if(norm==0)throw new IllegalStateException("private_embedding_empty");return vector;
    }
    private static byte[] encode(float[] values){var b=ByteBuffer.allocate(values.length*4);for(float f:values)b.putFloat(f);return b.array();}
    private static float[] decode(byte[] bytes){var b=ByteBuffer.wrap(bytes);float[] f=new float[bytes.length/4];for(int i=0;i<f.length;i++)f[i]=b.getFloat();return f;}
    private static void check(BooleanSupplier current){ChatRunExecutionContext.throwIfCancelled();if(!current.getAsBoolean())throw new CancellationException("focus_memory_stale");}
    public Result retrieve(FocusMemoryScope scope,String question,BooleanSupplier current){
        if(scope==null||!scope.recallEnabled())return Result.empty(Status.OFF,"recall_off");
        check(current);long started=System.nanoTime();var prior=TimeBudgetContext.get();
        TimeBudgetContext.set(new TimeBudget(ChatRunExecutionContext.capRequestWait(800)));
        try{
            var candidates=transaction(()->{
                if(!matches(scope,em.find(NovaFocusProfile.class,scope.namespace())))return null;
                return rows(scope.namespace());
            });
            if(candidates==null)return Result.empty(Status.BLOCKED_SCOPE,"scope_revision_changed");
            if(candidates.isEmpty())return Result.empty(Status.NO_AUTHORIZED_MEMORY,"no_saved_facts");
            if(candidates.size()>256)return Result.empty(Status.BLOCKED_SCOPE,"owner_fact_limit");
            check(current);var selected=new LinkedHashSet<String>();int vectorHits=0;String reason="";
            try{
                // Candidate selection is isolated BEFORE ANN search. No federated/public store is touched.
                var store=new InMemoryEmbeddingStore<String>();var q=Embedding.from(embed(question));
                for(var f:candidates)if(f.getEmbedding()!=null&&fingerprint.fingerprint().equals(f.getEmbeddingFingerprint())
                    &&f.getEmbedding().length==fingerprint.dimensions()*4)store.add(Embedding.from(decode(f.getEmbedding())),f.getSourceId());
                var hits=store.search(EmbeddingSearchRequest.builder().queryEmbedding(q).maxResults(6).minScore(0.65).build()).matches();
                for(var hit:hits)selected.add(hit.embedded());vectorHits=selected.size();
            }catch(CancellationException cancelled){throw cancelled;}
            catch(RuntimeException unavailable){reason="embedding_unavailable_"+unavailable.getClass().getSimpleName();}
            check(current);
            if(TimeBudgetContext.get().expired())return Result.empty(Status.TIMED_OUT,"memory_budget_exhausted");
            String folded=question.toLowerCase(Locale.ROOT);
            for(var f:candidates)if(selected.size()<6&&entities(f).stream().anyMatch(folded::contains))selected.add(f.getSourceId());
            int before=selected.size(),hops=0;
            boolean relation=folded.matches("(?s).*(관계|연결|함께|원인|related|connected).*");
            if(relation||selected.size()<2){
                var projection=candidates.stream().map(f->new BrainStateService.PrivateFact(scope.namespace(),f.getSourceId(),new LinkedHashSet<>(entities(f)))).toList();
                var expansion=BrainStateService.expandPrivate(scope.namespace(),projection,List.copyOf(selected),()->current.getAsBoolean()&&!TimeBudgetContext.get().expired());
                selected.addAll(expansion.sources());hops=expansion.hops();
            }
            int graphHits=selected.size()-before;var byId=new HashMap<String,FocusMemoryFact>();candidates.forEach(f->byId.put(f.getSourceId(),f));
            var evidence=new ArrayList<MemoryEvidence>();boolean truncated=false;
            for(String source:selected){
                var f=byId.get(source);if(f==null)continue;
                var e=new MemoryEvidence(f.getId(),f.getSourceId(),f.getRevision(),f.getScopeId(),"USER_SELECTED",f.getAssertionType(),
                    f.getText(),f.getEventTime(),f.getRecordedAt(),f.getRecordedAt(),null,f.getSupersedesId(),null,null);
                var next=new ArrayList<>(evidence);next.add(e);
                if(next.size()>4||ChatConversationContext.evidenceBytes(next)>3072){truncated=true;continue;}evidence.add(e);
            }
            check(current);if(!valid(scope,evidence))return Result.empty(Status.BLOCKED_SCOPE,"source_revision_changed");
            return new Result(List.copyOf(evidence),reason.isEmpty()?Status.OK:Status.DEGRADED,
                vectorHits>0?"SCOPED_VECTOR_LOCAL_GRAPH":"SCOPED_LEXICAL_LOCAL_GRAPH",vectorHits,graphHits,hops,
                ChatConversationContext.evidenceBytes(evidence),(System.nanoTime()-started)/1_000_000,truncated,reason);
        }finally{if(prior==null)TimeBudgetContext.clear();else TimeBudgetContext.set(prior);}
    }
    public boolean valid(FocusMemoryScope scope,List<MemoryEvidence> evidence){
        if(scope==null)return evidence.isEmpty();
        return transaction(()->{
            if(!matches(scope,em.find(NovaFocusProfile.class,scope.namespace())))return false;
            for(var e:evidence){var f=em.find(FocusMemoryFact.class,e.evidenceId());
                if(f==null||!scope.namespace().equals(f.getScopeId())||!f.isCurrentRevision()||f.getDeletedAt()!=null||f.getValidTo()!=null||f.getRevision()!=e.sourceRevision())return false;}
            return true;
        });
    }
}
