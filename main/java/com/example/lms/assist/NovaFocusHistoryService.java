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
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NovaFocusHistoryService.class);
    public record Settings(long settingsVersion,NovaFocusSettings settings) {}
    public record LensSettings(long settingsVersion,LensDisplayPrefs display) {}
    private static final String LENS_SETTINGS_CHANNEL="LENS_SETTINGS";
    public record Accepted(String turnId,Long chatSessionId,String state,boolean created) {}
    public record Pair(long sequence,String turnId,String state,String question,String answer) {}
    public record Page(List<Pair> turns,Long beforeSequence) {}
    public record Context(List<Pair> recent,String summary,List<Pair> relevant,List<com.example.lms.service.ChatConversationContext.Transcript> transcript,
                          NovaFocusSettings.AnswerSelection answerSelection,long settingsVersion,Integer answerLengthChars,boolean quickAnswerEnabled,Boolean webSearchEnabled,NovaFocusSettings.ReasoningPreset reasoningPreset,
                          String answerInstruction,NovaFocusSettings.AnswerPreset answerPreset) {
        public Context(List<Pair> recent,String summary,List<Pair> relevant,List<com.example.lms.service.ChatConversationContext.Transcript> transcript,NovaFocusSettings.AnswerSelection selection,long version,Integer length,boolean quick,Boolean web){
            this(recent,summary,relevant,transcript,selection,version,length,quick,web,NovaFocusSettings.ReasoningPreset.STANDARD,null,null);
        }
        public Context(List<Pair> recent,String summary,List<Pair> relevant,List<com.example.lms.service.ChatConversationContext.Transcript> transcript,NovaFocusSettings.AnswerSelection selection,long version,Integer length,boolean quick){
            this(recent,summary,relevant,transcript,selection,version,length,quick,null);
        }
        public Context(List<Pair> recent,String summary,List<Pair> relevant,List<com.example.lms.service.ChatConversationContext.Transcript> transcript,NovaFocusSettings.AnswerSelection selection,long version){
            this(recent,summary,relevant,transcript,selection,version,null,false);
        }
        public Context(List<Pair> recent,String summary,List<Pair> relevant){this(recent,summary,relevant,List.of());}
        public Context(List<Pair> recent,String summary,List<Pair> relevant,List<com.example.lms.service.ChatConversationContext.Transcript> transcript){
            this(recent,summary,relevant,transcript,NovaFocusSettings.AnswerSelection.defaults(),0);
        }
        public Context {recent=List.copyOf(recent);relevant=List.copyOf(relevant);transcript=List.copyOf(transcript);
            answerSelection=answerSelection==null?NovaFocusSettings.AnswerSelection.defaults():answerSelection;}
        @Override public String toString(){return "NovaFocusContext[redacted]";}
    }
    @PersistenceContext private EntityManager em;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    /** Server-side default for owners without stored settings; explicit toggles still win. */
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.default-enabled:false}")
    private boolean defaultEnabled;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.display-default-model:llmrouter.gemini-pro}")
    private String defaultDisplayModel="llmrouter.gemini-pro";

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
    /** Existing H2 profiles can be missed by Hibernate's case-sensitive metadata update. */
    @jakarta.annotation.PostConstruct
    void ensureMemoryRevisionColumn() {
        transaction(()->{
            boolean h2=em.unwrap(org.hibernate.Session.class).doReturningWork(c->
                "H2".equals(c.getMetaData().getDatabaseProductName()));
            if(h2)em.createNativeQuery("ALTER TABLE nova_focus_profile ADD COLUMN IF NOT EXISTS memory_revision BIGINT DEFAULT 0 NOT NULL").executeUpdate();
            return null;
        });
    }
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
    private static final int LEGACY_WAKE_LISTEN_MS=8000;
    private NovaFocusSettings serverDefaults(){
        var d=NovaFocusSettings.defaults();
        var selection=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.AUTO,null,
            new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,true,List.of("llmrouter.api3")));
        return new NovaFocusSettings(defaultEnabled,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),
            d.wakeListenTimeoutMs(),d.presentation(),d.recallEnabled(),d.rememberFactsEnabled(),
            d.snapshot(),selection,d.recentContext(),d.memory(),d.answerLengthChars(),d.quickAnswerEnabled(),d.webSearchEnabled(),d.effectiveReasoningPreset(),d.answerInstruction(),d.answerPreset(),d.cameraWakeWord());
    }
    /** Old-default rows upgrade to the walking-mode defaults on read; deliberate choices stay. */
    private NovaFocusSettings migrateLegacyDefaults(NovaFocusSettings stored){
        var legacySelection=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,defaultDisplayModel,
            new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of()));
        var selection=Objects.equals(stored.answerSelection(),legacySelection)?serverDefaults().answerSelection():stored.answerSelection();
        int listen=stored.wakeListenTimeoutMs()==LEGACY_WAKE_LISTEN_MS?NovaFocusSettings.defaults().wakeListenTimeoutMs():stored.wakeListenTimeoutMs();
        if(selection==stored.answerSelection()&&listen==stored.wakeListenTimeoutMs())return stored;
        return new NovaFocusSettings(stored.enabled(),stored.wakeWord(),stored.utteranceQuietMs(),stored.followupIdleMs(),
            listen,stored.presentation(),stored.recallEnabled(),stored.rememberFactsEnabled(),stored.snapshot(),
            selection,stored.recentContext(),stored.memory(),stored.answerLengthChars(),stored.quickAnswerEnabled(),
            stored.webSearchEnabled(),stored.reasoningPreset(),stored.answerInstruction(),stored.answerPreset(),stored.cameraWakeWord(),stored.exitWord());
    }
    private NovaFocusSettings decode(String json) {
        if(json==null)return serverDefaults();
        try{
            var raw=mapper.readTree(json);
            if(raw instanceof com.fasterxml.jackson.databind.node.ObjectNode node&&!node.hasNonNull("exitWord")){
                var defaults=NovaFocusSettings.defaults();String wake=node.path("wakeWord").asText(defaults.wakeWord());
                String camera=node.path("cameraWakeWord").asText(defaults.cameraWakeWordOrDefault());
                if(NovaFocusSettings.sameCommand(defaults.exitWordOrDefault(),wake)||NovaFocusSettings.sameCommand(defaults.exitWordOrDefault(),camera)){
                    for(String alternative:List.of("포커스 종료","집중 종료","finish"))
                        if(!NovaFocusSettings.sameCommand(alternative,wake)&&!NovaFocusSettings.sameCommand(alternative,camera)){node.put("exitWord",alternative);break;}
                    log.debug("[nova-focus] settings reason=legacy_exit_word_default_conflict");
                }
            }
            return mapper.treeToValue(raw,NovaFocusSettings.class);
        }
        catch(Exception e){throw new IllegalStateException("focus_settings_unreadable");}
    }
    private LensDisplayPrefs decodeLens(String json,int defaultHintTargetChars){
        var defaults=LensDisplayPrefs.defaults(defaultHintTargetChars);
        if(json==null)return defaults;
        try{return defaults.patch(mapper.readValue(json,LensDisplayPrefs.Patch.class));}
        catch(Exception failure){throw new IllegalStateException("lens_settings_unreadable");}
    }
    /** Presentation preferences reuse a separate purpose row; no Focus revision or conversation is written. */
    public LensSettings lensSettings(String owner,int defaultHintTargetChars){
        return transaction(()->{var p=em.find(NovaFocusProfile.class,scope(owner,LENS_SETTINGS_CHANNEL));
            return p==null?new LensSettings(0,LensDisplayPrefs.defaults(defaultHintTargetChars)):
                new LensSettings(p.getSettingsVersion(),decodeLens(p.getSettingsJson(),defaultHintTargetChars));});
    }
    public LensSettings lensSettings(String owner,Long expectedVersion,LensDisplayPrefs.Patch patch,boolean restoreDefaults,int defaultHintTargetChars){
        ensure(owner,LENS_SETTINGS_CHANNEL);
        return transaction(()->{var p=locked(owner,LENS_SETTINGS_CHANNEL);
            if(expectedVersion!=null&&p.getSettingsVersion()!=expectedVersion)throw new IllegalArgumentException("lens_settings_conflict");
            var current=decodeLens(p.getSettingsJson(),defaultHintTargetChars);
            var applied=restoreDefaults?LensDisplayPrefs.defaults(defaultHintTargetChars):current.patch(patch);
            try{p.setSettingsJson(mapper.writeValueAsString(applied));}
            catch(Exception failure){throw new IllegalStateException("lens_settings_unwritable");}
            p.setSettingsVersion(p.getSettingsVersion()+1);return new LensSettings(p.getSettingsVersion(),applied);
        });
    }
    public Settings settings(String owner,String channel) {
        return transaction(()->{var p=em.find(NovaFocusProfile.class,scope(owner,channel));
            return p==null?new Settings(0,serverDefaults()):new Settings(p.getSettingsVersion(),migrateLegacyDefaults(decode(p.getSettingsJson())));});
    }
    public Settings settings(String owner,String channel,long expected,NovaFocusSettings value) {
        Objects.requireNonNull(value);ensure(owner,channel);
        return transaction(()->{var p=locked(owner,channel);
            if(p.getSettingsVersion()!=expected)throw new IllegalArgumentException("focus_settings_conflict");
            var merged=value;
            if(value.snapshot()==null||value.snapshot().cameraAllowed()==null||value.answerSelection()==null||value.answerSelection().routing()==null||value.recentContext()==null||value.memory()==null||value.answerLengthChars()==null||value.quickAnswerEnabled()==null||value.webSearchEnabled()==null||value.reasoningPreset()==null||value.answerInstruction()==null||value.answerPreset()==null||value.cameraWakeWord()==null||value.exitWord()==null){ // Omitted optional blocks preserve server-owned values.
                var stored=decode(p.getSettingsJson());
                var selection=value.answerSelection()==null?stored.answerSelection():value.answerSelection();
                if(p.getSettingsJson()!=null&&value.answerSelection()!=null&&value.answerSelection().routing()==null&&stored.answerSelection()!=null)
                    selection=new NovaFocusSettings.AnswerSelection(selection.mode(),selection.modelId(),stored.answerSelection().routing());
                var snapshot=value.snapshot()==null?stored.snapshot():value.snapshot();
                // 구 클라이언트가 cameraAllowed를 생략하면 저장값을 유지한다(자동 촬영 토글만내도 권한이 풀리지 않게).
                if(value.snapshot()!=null&&value.snapshot().cameraAllowed()==null&&stored.snapshot()!=null&&stored.snapshot().cameraAllowed()!=null)
                    snapshot=new NovaFocusSettings.Snapshot(snapshot.enabled(),snapshot.source(),stored.snapshot().cameraAllowed());
                merged=new NovaFocusSettings(value.enabled(),value.wakeWord(),value.utteranceQuietMs(),value.followupIdleMs(),
                    value.wakeListenTimeoutMs(),value.presentation(),value.recallEnabled(),value.rememberFactsEnabled(),
                    snapshot,
                    selection,value.recentContext()==null?stored.recentContext():value.recentContext(),
                    value.memory()==null?stored.memory():value.memory(),
                    value.answerLengthChars()==null?stored.answerLengthChars():value.answerLengthChars(),
                    value.quickAnswerEnabled()==null?stored.quickAnswerEnabled():value.quickAnswerEnabled(),
                    value.webSearchEnabled()==null?stored.webSearchEnabled():value.webSearchEnabled(),
                    value.reasoningPreset()==null?stored.effectiveReasoningPreset():value.reasoningPreset(),
                    value.answerInstruction()==null?stored.answerInstruction():value.answerInstruction(),
                    value.answerPreset()==null?stored.answerPreset():value.answerPreset(),
                    value.cameraWakeWord()==null?stored.cameraWakeWord():value.cameraWakeWord(),
                    value.exitWord()==null?stored.exitWord():value.exitWord());
            }
            // Validate new saves with the actual matcher after legacy-field merge;
            // decoding existing profiles retains its compatibility contract.
            var sameWake=NovaWakeMatcher.find(merged.wakeWord(),merged.cameraWakeWordOrDefault());
            if(sameWake.isPresent()&&sameWake.get().start()==0&&sameWake.get().end()==merged.wakeWord().length())throw new IllegalArgumentException("invalid_nova_settings");
            try{p.setSettingsJson(mapper.writeValueAsString(merged));}catch(Exception e){throw new IllegalArgumentException("invalid_nova_settings");}
            p.setSettingsVersion(expected+1);return new Settings(p.getSettingsVersion(),merged);});
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
