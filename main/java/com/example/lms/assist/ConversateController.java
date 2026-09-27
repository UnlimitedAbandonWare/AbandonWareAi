package com.example.lms.assist;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import static com.example.lms.assist.ConversateSessionService.*;

@RestController
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class ConversateController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConversateController.class);
    @Value("${demo.interview.enabled:false}") private boolean interviewDemo;
    private final ConversateSessionService sessions;
    @Autowired(required=false) private PreparedMaterialReader materials;
    @Autowired(required=false) private ConversateAsrBridge asr;
    @Autowired(required=false) private com.example.lms.config.LocalLlmProcessManager localLlm;
    @Autowired(required=false) private com.example.lms.api.ChatGenerationAdmissionFilter costs;
    @Autowired(required=false) private InterviewDemoPublicAddress publicAddress;
    @Value("${conversate.fixture-enabled:false}") private boolean fixtures;
    public ConversateController(ConversateSessionService sessions){this.sessions=sessions;}
    private static final String BUILD_ID=buildId();
    private static String buildId(){
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(String path:List.of("static/conversate/app.js","static/conversate/index.html","static/conversate/style.css","com/example/lms/assist/ConversateController.class","com/example/lms/assist/ConversateSessionService.class","com/example/lms/assist/ConversateAsrBridge.class","com/example/lms/assist/ConversateQuestionPolicy.class","com/example/lms/assist/ConversateAnswerPipeline.class")){
                try(var input=new ClassPathResource(path).getInputStream()){byte[] bytes=input.readNBytes(1_048_577);if(bytes.length>1_048_576)return "unobserved";digest.update(bytes);}
            }return java.util.HexFormat.of().formatHex(digest.digest()).substring(0,16);
        }catch(Exception unavailable){return "unobserved";}
    }
    @GetMapping(value="/conversate",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<ClassPathResource> page(Authentication auth){owner(auth);return privateResponse(new ClassPathResource("static/conversate/index.html"));}
    @GetMapping("/api/assist/bootstrap")
    public ResponseEntity<?> bootstrap(Authentication auth,HttpServletRequest request){owner(auth);var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());return privateResponse(Map.of("fixtureEnabled",fixtures,"asrAvailable",asr!=null&&asr.available(),"csrfHeader",csrf==null?"":csrf.getHeaderName(),"csrfToken",csrf==null?"":csrf.getToken(),"outputGraceMs",5000,"storage","volatile","ollama",readiness(),"publicHttpsUrl",interviewDemo&&publicAddress!=null?publicAddress.currentOrigin():"","buildId",BUILD_ID,"stt",asr==null?Map.of("state","unavailable"):asr.diagnostics()));}
    private Map<String,Object> readiness(){
        if(localLlm==null)return Map.of("observed",false);
        var source=localLlm.diagnostics();var safe=new java.util.LinkedHashMap<String,Object>();safe.put("observed",true);
        for(String key:List.of("serviceResponding","modelConfigured","modelPresent","modelReady"))if(source.get(key) instanceof Boolean value)safe.put(key,value);
        for(String key:List.of("state","warmupStatus","warmupReason","reasonCode"))if(source.get(key) instanceof String value&&value.matches("[A-Za-z0-9_-]{1,64}"))safe.put(key,value);
        for(String key:List.of("warmupElapsedMs","warmupEvidenceAgeMs"))if(source.get(key) instanceof Number value)safe.put(key,value.longValue());
        return Map.copyOf(safe);
    }
    @PostMapping("/api/assist/sessions")
    public ResponseEntity<Snapshot> start(Authentication auth){
        // This demo only transports operator-approved text. It must not capture an authenticated
        // generation permit, and accidental generation on its session must still fail closed.
        Runnable costCheck=interviewDemo?()->{throw error(HttpStatus.FORBIDDEN,"demo_generation_disabled");}:costs==null?()->{}:costs.costCheckCurrentRequest();
        return privateResponse(sessions.start(owner(auth),costCheck));
    }
    @GetMapping("/api/assist/sessions/{id}")
    public ResponseEntity<Snapshot> status(Authentication auth,@PathVariable String id){return privateResponse(sessions.status(owner(auth),id));}
    @GetMapping("/api/assist/sessions/{id}/output/poll")
    public ResponseEntity<Snapshot> pollOutput(Authentication auth,@PathVariable String id,@RequestParam long epoch,@RequestParam String client){
        if(!interviewDemo)throw error(HttpStatus.NOT_FOUND,"demo_disabled");
        return privateResponse(sessions.pollOutput(owner(auth),id,epoch,client));
    }
    public record Receipt(long epoch,long version,String phase){public Receipt(long epoch,long version){this(epoch,version,null);}}
    public record DisplayCard(long epoch,String kind,String text,String requestId,List<String> sourceTitles,String requestState,long requestSequence){
        public DisplayCard(long epoch,String kind,String text,String requestId,List<String> sourceTitles){this(epoch,kind,text,requestId,sourceTitles,null,0);}
        public DisplayCard(long epoch,String kind,String text){this(epoch,kind,text,null,List.of());}
        @Override public String toString(){return "DisplayCard[redacted]";}
    }
    /** Explicit operator text only; reuses the existing volatile output lifecycle, never RAG generation. */
    @PostMapping("/api/assist/sessions/{id}/card")
    public ResponseEntity<Snapshot> displayCard(Authentication auth,@PathVariable String id,@RequestBody DisplayCard request){
        if(!interviewDemo)throw error(HttpStatus.NOT_FOUND,"demo_disabled");
        String owner=owner(auth);
        if("status".equals(request.kind())){
            if(request.text()!=null||(request.sourceTitles()!=null&&!request.sourceTitles().isEmpty()))throw error(HttpStatus.BAD_REQUEST,"invalid_request_status");
            return privateResponse(sessions.publishRequestStatus(owner,id,request.epoch(),request.requestId(),request.requestState(),request.requestSequence()));
        }
        if(request.kind()==null||!List.of("question","hint","answer").contains(request.kind()))throw error(HttpStatus.BAD_REQUEST,"invalid_card_kind");
        String text=request.text();
        if(text==null||text.isBlank()||text.codePointCount(0,text.length())>280)throw error(HttpStatus.BAD_REQUEST,"invalid_card_length");
        sessions.status(owner,id);
        var card=new Card("SHOW",request.kind().toUpperCase(java.util.Locale.ROOT),text,List.of(),System.currentTimeMillis()+60_000,request.requestId(),request.sourceTitles());
        if(!sessions.publish(owner,id,request.epoch(),card))throw error(HttpStatus.CONFLICT,"stale_or_paused_output");
        var snapshot=sessions.status(owner,id);
        LOG.info("interview.display.accepted kind={} chars={} epoch={} version={} outputs={}",request.kind(),text.codePointCount(0,text.length()),snapshot.epoch(),snapshot.version(),snapshot.outputConnections());
        return privateResponse(snapshot);
    }
    @PostMapping("/api/assist/sessions/{id}/ack")
    public ResponseEntity<Snapshot> acknowledge(Authentication auth,@PathVariable String id,@RequestBody Receipt request){var snapshot=sessions.acknowledge(owner(auth),id,request.epoch(),request.version(),request.phase());if(interviewDemo)LOG.debug("interview.display.receiver_ack epoch={} version={} outputs={}",request.epoch(),request.version(),snapshot.outputConnections());return privateResponse(snapshot);}
    @GetMapping(value="/api/assist/sessions/{id}/output",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Snapshot>>> output(Authentication auth,@PathVariable String id,@RequestParam long epoch){return privateResponse(sessions.output(owner(auth),id,epoch));}
    public record Control(long epoch,String action){}
    public record Epoch(long epoch){}
    public record Selection(long epoch,String sessionId,List<String> sourceIds){}
    public record Transcript(long epoch,ConversateQuestionPolicy.Utterance utterance){}
    public record AudioChunk(long epoch,long sequence,String pcm){@Override public String toString(){return "AudioChunk[redacted]";}}
    @PostMapping("/api/assist/sessions/{id}/audio/start")
    public ResponseEntity<?> startAudio(Authentication auth,@PathVariable String id,@RequestBody Epoch request){String owner=owner(auth);if(asr==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"asr_disabled");return privateResponse(asr.start(owner,id,request.epoch()));}
    @PostMapping("/api/assist/sessions/{id}/audio/chunk")
    public ResponseEntity<Snapshot> audio(Authentication auth,@PathVariable String id,@RequestBody AudioChunk request){String owner=owner(auth);if(asr==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"asr_disabled");return privateResponse(asr.chunk(owner,id,request.epoch(),request.sequence(),request.pcm()));}
    @GetMapping("/api/assist/materials")
    public ResponseEntity<?> choices(Authentication auth,@RequestParam String sessionId){owner(auth);return privateResponse(reader().choices(auth.getName(),sessionId));}
    @PostMapping("/api/assist/sessions/{id}/materials")
    public ResponseEntity<Snapshot> select(Authentication auth,@PathVariable String id,@RequestBody Selection request){String owner=owner(auth);sessions.status(owner,id);var selected=reader().read(auth.getName(),request.sessionId(),request.sourceIds());return privateResponse(sessions.prepare(owner,id,request.epoch(),selected));}
    @PostMapping("/api/assist/sessions/{id}/utterance")
    public ResponseEntity<Snapshot> utterance(Authentication auth,@PathVariable String id,@RequestBody Transcript request,HttpServletRequest http){return privateResponse(sessions.submit(owner(auth),id,request.epoch(),request.utterance(),"direct",http.getHeader("X-Request-Id")));}
    @PostMapping("/api/assist/sessions/{id}/fixture-materials")
    public ResponseEntity<Snapshot> fixtureMaterials(Authentication auth,@PathVariable String id,@RequestBody Epoch request){String owner=owner(auth);if(!fixtures)throw error(HttpStatus.NOT_FOUND,"fixture_disabled");return privateResponse(sessions.prepare(owner,id,request.epoch(),List.of(new PreparedMaterialReader.Material("fixture-warranty","보증 기간은 2년입니다.\n침수 손상은 보증하지 않습니다."))));}
    private PreparedMaterialReader reader(){if(materials==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"material_index_unavailable");return materials;}
    @PostMapping("/api/assist/sessions/{id}/control")
    public ResponseEntity<Snapshot> control(Authentication auth,@PathVariable String id,@RequestBody Control control){return privateResponse(sessions.control(owner(auth),id,control.epoch(),control.action()==null?"":control.action()));}
    @PostMapping("/api/assist/sessions/{id}/fixture")
    public ResponseEntity<Snapshot> fixture(Authentication auth,@PathVariable String id,@RequestBody Epoch request){String owner=owner(auth);if(!fixtures)throw error(HttpStatus.NOT_FOUND,"fixture_disabled");return privateResponse(sessions.fixture(owner,id,request.epoch()));}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).cacheControl(CacheControl.noStore()).body(Map.of("reason",error.getReason()==null?"assist_failed":error.getReason()));}
    /** Also covers a read-only transaction failing before the prepared-reader method is entered. */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    ResponseEntity<?> materialUnavailable(){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(Map.of("reason","material_index_unavailable"));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("reason","invalid_assist_request"));}
    private static <T> ResponseEntity<T> privateResponse(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no").header("Referrer-Policy","no-referrer").body(body);}
    private String owner(Authentication auth){if(interviewDemo)return "local-interview-demo";if(auth==null||!auth.isAuthenticated()||auth instanceof AnonymousAuthenticationToken||auth.getName()==null||auth.getName().isBlank())throw error(HttpStatus.UNAUTHORIZED,"authentication_required");return org.apache.commons.codec.digest.DigestUtils.sha256Hex("user:"+auth.getName());}
}
