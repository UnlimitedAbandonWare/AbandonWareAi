package com.example.lms.assist;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Short DB transactions only. No provider work, audio, or polling timers here. */
@Service
public class NovaFocusHistoryService {
    public record Settings(long settingsVersion,NovaFocusSettings settings) {}
    public record Accepted(String turnId,Long chatSessionId,String state,boolean created) {}
    public record Pair(long sequence,String turnId,String state,String question,String answer) {}
    public record Page(List<Pair> turns,Long beforeSequence) {}
    public record Context(List<Pair> recent,String summary,List<Pair> relevant) {}
    @PersistenceContext private EntityManager em;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public NovaFocusHistoryService(PlatformTransactionManager manager,ObjectMapper mapper,ChatHistoryService history) {
        this.mapper=mapper;this.tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    static String scope(String owner,String channel) {
        if(owner==null||owner.isBlank()||owner.length()>128||channel==null||channel.isBlank()||channel.length()>128)
            throw new IllegalArgumentException("invalid_focus_scope");
        return digest(owner+"\n"+channel+"\nNOVA_FOCUS");
    }
    static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("focus_digest_unavailable");}
    }
    // Key material is derived from the unpersisted request identity, never the body.
    // Receipts are linkable metadata, not anonymized data; no raw input enters this ledger.
    private static String fingerprint(String owner,String channel,String request,String question){
        try{
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(digest(scope(owner,channel)+"\n"+request).getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(question.getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException failure){throw new IllegalStateException("focus_fingerprint_unavailable");}
    }
    private <T>T transaction(Supplier<T> action){return tx.execute(status->action.get());}
    private NovaFocusProfile locked(String owner,String channel) {
        var p=em.find(NovaFocusProfile.class,scope(owner,channel),LockModeType.PESSIMISTIC_WRITE);
        if(p==null)throw new IllegalStateException("focus_profile_missing");
        return p;
    }
    private void ensure(String owner,String channel) {
        String id=scope(owner,channel);
        if(transaction(()->em.find(NovaFocusProfile.class,id)!=null))return;
        try{transaction(()->{var p=new NovaFocusProfile();p.setId(id);p.setOwnerKey(owner);p.setChannel(channel);em.persist(p);em.flush();return null;});}
        catch(RuntimeException race){if(!transaction(()->em.find(NovaFocusProfile.class,id)!=null))throw race;}
    }
    private NovaFocusSettings decode(String json) {
        if(json==null)return NovaFocusSettings.defaults();
        try{return mapper.readValue(json,NovaFocusSettings.class);}
        catch(Exception e){throw new IllegalStateException("focus_settings_unreadable");}
    }
    public Settings settings(String owner,String channel) {
        return transaction(()->{var p=em.find(NovaFocusProfile.class,scope(owner,channel));
            return p==null?new Settings(0,NovaFocusSettings.defaults()):new Settings(p.getSettingsVersion(),decode(p.getSettingsJson()));});
    }
    public Settings settings(String owner,String channel,long expected,NovaFocusSettings value) {
        Objects.requireNonNull(value);ensure(owner,channel);
        return transaction(()->{var p=locked(owner,channel);
            if(p.getSettingsVersion()!=expected)throw new IllegalArgumentException("focus_settings_conflict");
            try{p.setSettingsJson(mapper.writeValueAsString(value));}catch(Exception e){throw new IllegalArgumentException("invalid_nova_settings");}
            p.setSettingsVersion(expected+1);return new Settings(p.getSettingsVersion(),value);});
    }
    /** Opening alone creates the unique durable room, never a user message. */
    public Long open(String owner,String channel) {
        ensure(owner,channel);
        return transaction(()->room(locked(owner,channel)).getId());
    }
    private ChatSession room(NovaFocusProfile p) {
        if(p.getChatSession()==null){var s=new ChatSession("Nova",p.getOwnerKey(),"ANON");em.persist(s);em.flush();p.setChatSession(s);}
        return p.getChatSession();
    }
    /** Read-only preflight makes a retried manual request conflict synchronous after restart. */
    public boolean knownRequest(String owner,String channel,String request,String question){
        return transaction(()->{
            var hashes=em.createQuery("select t.inputHash from NovaFocusAcceptance t where t.profile.id=:p and t.requestKey=:k",String.class)
                .setParameter("p",scope(owner,channel)).setParameter("k",digest(request)).setMaxResults(1).getResultList();
            if(hashes.isEmpty()){
                var legacy=em.createQuery("select t.inputHash from NovaFocusTurn t where t.profile.id=:p and t.requestKey=:k",String.class)
                    .setParameter("p",scope(owner,channel)).setParameter("k",digest(request)).setMaxResults(1).getResultList();
                if(legacy.isEmpty())return false;
                if(!digest(question).equals(legacy.get(0)))throw new IllegalArgumentException("focus_request_conflict");
                return true;
            }
            if(!fingerprint(owner,channel,request,question).equals(hashes.get(0)))throw new IllegalArgumentException("focus_request_conflict");
            return true;
        });
    }
    public Accepted accept(String owner,String channel,String activation,String request,String question) {
        if(question==null||question.isBlank()||question.codePointCount(0,question.length())>2000||activation==null||activation.length()>128||request==null||request.length()>256)
            throw new IllegalArgumentException("invalid_focus_question");
        ensure(owner,channel);
        return transaction(()->{
            var p=locked(owner,channel);String key=digest(request),hash=fingerprint(owner,channel,request,question);
            var found=em.createQuery("select t from NovaFocusAcceptance t where t.profile.id=:p and t.requestKey=:k",NovaFocusAcceptance.class)
                .setParameter("p",p.getId()).setParameter("k",key).setMaxResults(1).getResultList();
            if(!found.isEmpty()){var old=found.get(0);if(!hash.equals(old.getInputHash()))throw new IllegalArgumentException("focus_request_conflict");
                return new Accepted(old.getId(),room(p).getId(),old.getState(),false);}
            var legacy=em.createQuery("select t from NovaFocusTurn t where t.profile.id=:p and t.requestKey=:k",NovaFocusTurn.class)
                .setParameter("p",p.getId()).setParameter("k",key).setMaxResults(1).getResultList();
            if(!legacy.isEmpty()){
                var old=legacy.get(0);if(!digest(question).equals(old.getInputHash()))throw new IllegalArgumentException("focus_request_conflict");
                return new Accepted(old.getId(),room(p).getId(),old.getState(),false);
            }
            var s=room(p);
            var turn=new NovaFocusAcceptance();turn.setId(UUID.randomUUID().toString());turn.setProfile(p);turn.setRequestKey(key);turn.setInputHash(hash);
            turn.setActivationId(activation);turn.setSequenceNumber(p.getNextSequence()+1);p.setNextSequence(turn.getSequenceNumber());
            turn.setState("ACCEPTED");em.persist(turn);
            return new Accepted(turn.getId(),s.getId(),"ACCEPTED",true);
        });
    }
    /** A restart/reopen fences incomplete old work; it never regenerates it. */
    public void recover(String owner,String channel) {
        transaction(()->{var p=em.find(NovaFocusProfile.class,scope(owner,channel),LockModeType.PESSIMISTIC_WRITE);
            if(p!=null)em.createQuery("update NovaFocusAcceptance t set t.state='OUTCOME_UNKNOWN' where t.profile.id=:p and t.state in ('ACCEPTED','GENERATING')")
                .setParameter("p",p.getId()).executeUpdate();return null;});
    }
    public boolean terminal(String owner,String channel,String id,String state,String answer) {
        if(!Set.of("COMPLETED","CANCELLED","OUTCOME_UNKNOWN").contains(state))throw new IllegalArgumentException("invalid_focus_terminal");
        return transaction(()->{
            var p=locked(owner,channel);
            var turn=em.find(NovaFocusAcceptance.class,id,LockModeType.PESSIMISTIC_WRITE);
            if(turn==null||!turn.getProfile().getId().equals(p.getId())||!Set.of("ACCEPTED","GENERATING").contains(turn.getState()))return false;
            if("COMPLETED".equals(state)){
                if(answer==null||answer.isBlank())throw new IllegalArgumentException("empty_focus_answer");
            }
            turn.setState(state);return true;
        });
    }
    public Page page(String owner,String channel,Long before,int limit) {
        int bounded=Math.max(1,Math.min(limit,30));long cursor=before==null?Long.MAX_VALUE:before;
        return transaction(()->{
            var rows=em.createQuery("select t from NovaFocusAcceptance t where t.profile.id=:p and t.sequenceNumber<:c order by t.sequenceNumber desc",NovaFocusAcceptance.class)
                .setParameter("p",scope(owner,channel)).setParameter("c",cursor).setMaxResults(bounded+1).getResultList();
            boolean more=rows.size()>bounded;var selected=rows.stream().limit(bounded).map(NovaFocusHistoryService::pair).toList();
            return new Page(selected,more?selected.get(selected.size()-1).sequence():null);
        });
    }
    private static Pair pair(NovaFocusAcceptance t){return new Pair(t.getSequenceNumber(),t.getId(),t.getState(),"","");}
    public Context context(String owner,String channel,String question) {
        scope(owner,channel);
        // Historical conversation consent is unknown. Active context lives only in the Focus slot.
        return new Context(List.of(),"",List.of());
    }
    // Display caps count code points; memory uses a separate UTF-8 byte ceiling.
    static String clip(String text,int allowance){if(text==null)return "";int n=text.codePointCount(0,text.length());return n<=allowance?text:text.substring(0,text.offsetByCodePoints(0,allowance));}
    static String memoryClip(String text,int bytes){
        if(text==null)return "";int end=0,used=0;
        while(end<text.length()){int cp=text.codePointAt(end),cost=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
            if(used+cost>bytes)break;used+=cost;end+=Character.charCount(cp);}
        return text.substring(0,end);
    }
    // 1,400 recent + 600 summary + 600 relevant bytes leaves framing headroom.
    private static Pair bounded(Pair p,int allowance){return new Pair(p.sequence(),p.turnId(),p.state(),memoryClip(p.question(),allowance),memoryClip(p.answer(),allowance));}
    public void summarize(Long session){/* Ephemeral Focus never creates a durable summary. */}
}
