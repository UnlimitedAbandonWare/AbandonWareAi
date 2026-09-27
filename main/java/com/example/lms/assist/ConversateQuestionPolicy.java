package com.example.lms.assist;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import static com.example.lms.assist.ConversateSessionService.error;

/** Bounded volatile utterance ledger. No transcript is retained by the dedup ledger. */
public final class ConversateQuestionPolicy {
    public record Utterance(String utteranceId,String questionId,int revision,boolean isFinal,String text,
                            Double confidence,List<com.example.lms.service.stt.DeepgramSttService.Word> words) {
        public Utterance(String utteranceId,String questionId,int revision,boolean isFinal,String text){this(utteranceId,questionId,revision,isFinal,text,null,List.of());}
        public Utterance {
            words=words==null?List.of():List.copyOf(words);
            if(words.size()>1024||(confidence!=null&&(!Double.isFinite(confidence)||confidence<0||confidence>1)))throw error(HttpStatus.BAD_REQUEST,"invalid_speech_metadata");
        }
        @Override public String toString(){return "Utterance[redacted]";}
    }
    public record Decision(String kind,String question){}
    record CueDecision(String decision,String reason,boolean complex,boolean topicChanged,boolean contextRelevant,int quality){}
    private record Seen(int revision,String hash,boolean finalized){}
    private final Map<String,Seen> seen=new LinkedHashMap<>();
    private String lastQuestionHash="";
    private String lastQuestionShapeHash="";
    private static final Pattern MONEY=Pattern.compile("[+-]?\\d+(?:[,.]\\d+)*\\s*(?:조원|억원|만원|천원|원)");
    private static final Pattern DURATION=Pattern.compile("[+-]?\\d+(?:[,.]\\d+)*\\s*(?:개월|시간|년|월|주|일|분|초)");
    private static final Pattern NUMBER=Pattern.compile("[+-]?\\d+(?:[,.]\\d+)*");
    private static final Pattern BACKCHANNEL=Pattern.compile("^(아[ ,]*)?(네|예|응|그렇군요|알겠습니다|고마워요|감사합니다)[.! ,]*$");
    private static final Pattern QUESTION=Pattern.compile("[?？]|얼마|언제|어디|누가|무엇|왜|어떻게|몇|인가요|되나요|나요|까요|습니까|다음 질문|대화 제안|(?:뜻|의미|개념|용어).*(?:설명|알려)");
    public Decision accept(Utterance input){
        return accept(input,false);
    }
    /** A wearer-confirmed field is an explicit request, unlike ambient conversation. */
    public Decision acceptExplicit(Utterance input){return accept(input,true);}
    /** Deduplicate delivery identity, never words spoken again at a new position. */
    public Decision acceptForCue(Utterance input){return accept(input,false,true);}
    /** Admission is local. A caption or an ambient statement is not a paid classification request. */
    static CueDecision cueDecision(String input,List<String> recent){
        String text=Normalizer.normalize(input,Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");
        if(recent==null)recent=List.of();
        String priorTopic=recent.stream().filter(Objects::nonNull)
                .filter(turn->!turn.startsWith("[assistant cue]")&&!turn.startsWith("[User-selected TXT background;"))
                .reduce((first,last)->last).orElse("");
        boolean pendingQuestion=!recent.isEmpty()&&recent.get(recent.size()-1).startsWith("[assistant cue]")
                &&recent.get(recent.size()-1).matches("(?s).*[?？].*");
        boolean acknowledgement=text.matches("(?iu)^(?:(?:아|네|예|응|어|음|알겠어요|알겠습니다|그렇군요|고마워요|감사합니다|안녕|안녕하세요|좋아요|okay|ok|thanks|thank you)[\\s,.!。]*)+$");
        boolean resolved=text.matches("(?s)^(?:네[, ]*)?(?:이제|잘|완전히)?\\s*(?:이해했어요|이해했습니다|알겠어요|해결됐어요|해결됐습니다)[.!。 ]*$");
        boolean followup=!recent.isEmpty()&&(text.matches("(?s)^(?:그럼|그러면|그건|그게|그거|그것|그 내용|그 원리|이거|이것|아니|정정|다시).*" )
                ||text.matches(".{1,20}(?:은|는|도)(?:요)?[?？.!。 ]*"));
        boolean contextTransform=text.matches("(?s)^(?:(?:방금|앞서)\\s*(?:말한|설명한)?\\s*(?:내용|답변)|위 내용|이전 답변|그 내용|그 답변)(?:을|를)?\\s*(?:(?:한 문장으로|짧게|간단히|영어로|한국어로|다시)\\s*)?(?:요약|정리|번역|설명)해\\s*(?:줘|주세요|줄래)?[.!?。？ ]*$" );
        boolean contextRecall=text.matches("(?s)^(?:아까|방금|앞서|이전에)\\s*(?:(?:내가|제가|우리가)\\s*)?(?:말한|정한|알려 준|설명한)\\s*.{0,30}(?:시간|장소|일정|이름|준비물)(?:은|는|이|가)?\\s*(?:몇\\s*시|언제|어디|뭐|무엇|누구)(?:야|였지|였어|인가요|예요)?[?？.! ]*$")
                &&!text.matches("(?s).*(?:하고|그리고|추가|대신).*" );
        boolean topic=text.matches("(?s).*(?:다른 주제|새 주제|주제를 바꿔|화제를 바꿔).*" )
                ||lexicalTopicShift(text,priorTopic,followup||contextRecall||contextTransform);
        boolean wordingRequest=text.matches("(?s).*(?:문장|표현|멘트|답장)(?:을|를)?\\s*(?:(?:짧게|정중하게|간단히|한국어로|영어로)\\s*)*(?:제안해|작성해|만들어|다듬어)\\s*(?:줘|주세요|줄래)[.!?。？ ]*$");
        // Drafting a question does not require knowing its answer. Informative
        // statements still use evidence, as do explicit source/current-fact requests.
        boolean conversationalWording=wordingRequest&&text.matches("(?s).*(?:묻는|물어보는|질문하는|(?:감사|사과|거절|인사|안부)(?:를|을)?\\s*전하는)\\s*(?:한국어|영어)?\\s*(?:문장|표현|멘트|답장).*");
        boolean request=wordingRequest||contextTransform||QUESTION.matcher(text).find()
                ||text.matches("(?s).*교차\\s*검증해\\s*(?:줘|주세요)[.!?？ ]*$")
                ||text.matches("(?s).*(?:뭐야|뭐냐|뭐예요|뭔가요|뭘까|누구|어때|해결돼|알려\\s*줘|알려\\s*주세요|설명해|비교해|정리해|추천해|확인해|도와\\s*줘|말해\\s*줘|답변.*안.*나|질문).*" )
                ||needsHint(text)||followup||pendingQuestion&&acknowledgement;
        if(text.isBlank()||resolved||acknowledgement&&!pendingQuestion||!request)
            return new CueDecision("NO_CUE",resolved?"RESOLVED":"SMALL_TALK",false,topic,!topic,1);
        boolean complex=text.matches("(?s).*(?:복잡|여러 조건|충돌|최적|최소 비용|최대 효과|계산|증명|일정|의학|복용|법률|투자|안전).*" );
        boolean expert=complex&&text.matches("(?s).*(?:전문가 수준|고난도|엄밀한 증명).*" );
        // Stable concepts use one short generation; current/private/precise claims
        // and explicit verification retain the existing retrieval owner.
        boolean conversational=conversationalWording||needsHint(text)||text.matches("(?s).*(?:도움이 필요|도움을 받을).*" )||pendingQuestion&&acknowledgement
                ||text.matches("(?s).*(?:어떻게|뭐라고).*(?:답|말|부탁)|.*(?:답할까요|답하면|말할까요|부탁할까요|도움을 받을|대화 제안|다음 질문).*" )
                ||complex&&text.matches("(?s).*(?:판단|계산|일정|최적).*" );
        boolean explicitEvidence=text.matches("(?si).*(?:https?://|공식|출처|근거|검색|검증|사실\\s*확인|현재|최신|오늘.*(?:날씨|가격|환율|주가)).*" );
        boolean contextOnly=(contextTransform||contextRecall)&&!recent.isEmpty()&&!topic&&!explicitEvidence
                &&!text.matches("(?s).*(?:검색|교차.*검증|사실.*확인|추가.*정보|새로운.*정보).*" );
        boolean general=!complex&&!requiresEvidence(text)&&(generalKnowledge(text)
                ||followup&&!topic&&generalKnowledge(priorTopic));
        boolean rag=!contextOnly&&(explicitEvidence||!conversational&&!general);
        return new CueDecision(rag?"RAG_CUE":"CUE",rag?"EXTERNAL_FACT":general?"GENERAL_KNOWLEDGE":"HELPFUL_RESPONSE",complex,topic,!topic,expert?4:complex?3:1);
    }
    private static boolean requiresEvidence(String text){
        if(text.matches("(?si).*(?:우리|저희|(?:내|제)\\s|회사|제품|기종|개인|비밀번호|비밀|계좌|잔액|인증|연락처|주민등록|내일|내년|이번|요즘).*"))return true;
        return text.matches("(?si).*(?:https?://|공식|출처|근거|검색|검증|사실\\s*확인|현재|최신|오늘|지금|최근|신규|[0-9]|수치|인구|가격|환율|주가|요금|버전|출시|대통령|누구|누가|언제|어디|얼마|몇|날씨|우리\\s*회사|이\\s*회사|우리\\s*제품|이\\s*제품|이\\s*약|복용|의학|법률|투자|안전|환불|반환|보증|정책|규정|조건|기한|기간|규칙).*" );
    }
    private static boolean generalKnowledge(String text){
        return !text.isBlank()&&!requiresEvidence(text)
                &&text.matches("(?si).*(?:정의|원리|개념|뜻|의미|차이|비교|어떻게.*달라|뭐야|뭐냐|뭐예요|뭔가요|무엇인가요).*" );
    }
    private Decision accept(Utterance input,boolean explicit){
        return accept(input,explicit,false);
    }
    private Decision accept(Utterance input,boolean explicit,boolean cueGate){
        if(input==null||!id(input.utteranceId())||!id(input.questionId())||input.revision()<0||input.revision()>1_000_000||input.text()==null||input.text().length()>8192)throw error(HttpStatus.BAD_REQUEST,"invalid_utterance");
        String text=Normalizer.normalize(input.text(),Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");
        String hash=org.apache.commons.codec.digest.DigestUtils.sha256Hex(text),key=input.questionId()+":"+input.utteranceId();
        var old=seen.get(key);
        if(old!=null&&old.finalized()&&!input.isFinal())return new Decision("STALE","");
        if(old!=null&&old.finalized()&&input.isFinal()&&hash.equals(old.hash()))return new Decision("DUPLICATE","");
        if(old!=null){if(input.revision()<old.revision())return new Decision("STALE","");
            if(input.revision()==old.revision()){
                if(old.finalized()&&!hash.equals(old.hash()))throw error(HttpStatus.CONFLICT,"utterance_revision_conflict");
                if(hash.equals(old.hash())&&(old.finalized()||!input.isFinal()))return new Decision("DUPLICATE","");
            }}
        seen.put(key,new Seen(input.revision(),hash,input.isFinal()));while(seen.size()>64)seen.remove(seen.keySet().iterator().next());
        if(!input.isFinal())return new Decision("PARTIAL","");
        if(cueGate)return new Decision(text.isBlank()?"BACKCHANNEL":"CUE_PENDING",text);
        if(explicit){
            if(text.isBlank())return new Decision("BACKCHANNEL","");
            if(hash.equals(lastQuestionHash))return new Decision("DUPLICATE","");
            lastQuestionHash=hash;return new Decision("QUESTION",text);
        }
        if(text.isBlank()||BACKCHANNEL.matcher(text).matches())return new Decision("BACKCHANNEL","");
        if(text.matches(".*(질문|문제).*(해결|이해).*")){lastQuestionHash="";lastQuestionShapeHash="";return new Decision("RESOLVED","");}
        boolean correction=(old!=null&&old.finalized()&&!old.hash().equals(hash))||text.matches("^(아니[, ]|정정|수정).*" );
        boolean request=text.matches(".*(?:설명|비교|정리)해\\s*(?:줘|주세요|주십시오)[.!。 ]*$");
        boolean hint=needsHint(text);
        if(!correction&&!request&&!hint&&!QUESTION.matcher(text).find())return new Decision("NEW_INFORMATION","");
        if(hash.equals(lastQuestionHash))return new Decision("DUPLICATE","");
        String shape=questionShapeHash(text);
        correction=correction||(!lastQuestionShapeHash.isEmpty()&&shape.equals(lastQuestionShapeHash));
        lastQuestionHash=hash;lastQuestionShapeHash=shape;return new Decision(correction?"CORRECTION":hint?"HINT":"QUESTION",text);
    }
    /** Explicit difficulty at the end of an ambient utterance, without rewriting its meaning. */
    static boolean needsHint(String text){return text!=null
            &&!text.strip().matches(".*(?:안|별로)\\s*헷갈(?:립니다|려요)[.!。 ]*$")
            &&text.strip().matches(".*(?:잘\\s*모르겠(?:어요|습니다)|결정하기\\s*어렵(?:습니다|네요)|헷갈(?:립니다|려요)|도움이\\s*필요(?:합니다|해요))[.!。 ]*$");}
    /** Classification only: the original question, quantities, units and negation are never rewritten. */
    private static String questionShapeHash(String text){
        String shape=MONEY.matcher(text).replaceAll("#money");
        shape=DURATION.matcher(shape).replaceAll("#duration");shape=NUMBER.matcher(shape).replaceAll("#");
        shape=shape.replaceAll("하지\\s*않","하").replaceAll("되지\\s*않","되")
                .replaceAll("(?<![가-힣])(?:안|못)\\s+","").replace("아닌가요","인가요").replace("없나요","있나요")
                .replaceAll("\\s+","");
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(shape);
    }
    /** Latest and prior turns share no content token: treat as a new topic without an LLM judge. Follow-ups stay. */
    static boolean lexicalTopicShift(String latest,String prior,boolean followup){
        if(followup||prior==null||prior.isBlank()||latest==null||latest.isBlank())return false;
        var now=contentTokens(latest);var then=contentTokens(prior);
        if(now.size()<2||then.size()<2)return false;
        for(String token:now)if(then.contains(token))return false;
        return true;
    }
    private static final Set<String> STOP=Set.of("그리고","그런데","그래서","오늘","지금","그냥","조금","아주","정말","이것","그것","저것","있는","없는","하는","된","the","and","for","with");
    private static Set<String> contentTokens(String text){
        var out=new LinkedHashSet<String>();
        for(String raw:text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")){
            String token=raw.replaceFirst("(은|는|이|가|을|를|에|에서|으로|로|와|과|도|만|부터|까지|요|야)$","");
            if(token.length()<2||STOP.contains(token))continue;
            out.add(token);
        }
        return out;
    }
    public void clear(){seen.clear();lastQuestionHash="";lastQuestionShapeHash="";}
    int retainedCount(){return seen.size();}
    private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9._:-]{1,80}");}
}
