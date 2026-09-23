package com.example.lms.assist;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative extraction checks, not a claim of general semantic entailment. Raw output stays here. */
final class ConversateCardVerifier {
    record Verified(String reason,ConversateSessionService.Card card){}
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(16384).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern QUANTITY=Pattern.compile("[0-9]+(?:[.,][0-9]+)*\\s*(?:만원|개월|시간|kg|km|년|원|일|분|초|%|개)?");
    private static final List<String> CONDITIONS=List.of("미개봉","개봉","이내","이상","이하","초과","미만","경우","한해","조건","침수","제외","이전","이후","까지","부터");
    static Verified verifySuggestion(String raw,long now){
        try{
            if(raw==null||raw.length()>256)return rejected("PARSE_FAILED",now);
            var root=JSON.readTree(raw);fields(root,"choice");var choice=root.get("choice");
            if(!choice.isIntegralNumber()||!choice.canConvertToInt()||choice.intValue()<0||choice.intValue()>=ConversateCardPrompt.SUGGESTIONS.size())return rejected("PARSE_FAILED",now);
            return new Verified("GENERATED",new ConversateSessionService.Card("SHOW","SUGGESTION",ConversateCardPrompt.SUGGESTIONS.get(choice.intValue()),List.of(),now+20_000));
        }catch(Exception invalid){return rejected("PARSE_FAILED",now);}
    }
    static Verified verify(String raw,List<ConversateCardPrompt.Evidence> evidence,boolean complex,long now){
        try {
            if(raw==null||raw.length()>16384)return rejected("PARSE_FAILED",now);
            var root=JSON.readTree(raw);fields(root,"positive","negative","neutral");
            var positives=root.get("positive");var negatives=root.get("negative");var neutral=root.get("neutral");
            if(!positives.isArray()||positives.isEmpty()||positives.size()>(complex?2:1)||!negatives.isArray()||negatives.size()>(complex?2:0))throw new IllegalArgumentException();
            boolean legacy=neutral!=null&&neutral.has("text");
            if(legacy)fields(neutral,"decision","candidate","text","evidenceIds");else fields(neutral,"decision","candidate");
            String decision=string(neutral.get("decision"));if(!Set.of("SHOW","ASK","HOLD").contains(decision)||!neutral.get("candidate").isIntegralNumber()||!neutral.get("candidate").canConvertToInt())throw new IllegalArgumentException();
            int chosen=neutral.get("candidate").intValue();if(chosen<0||chosen>=positives.size())throw new IllegalArgumentException();
            var available=new LinkedHashMap<String,ConversateCardPrompt.Evidence>();evidence.forEach(e->available.put(e.id(),e));
            for(var candidate:positives){fields(candidate,"kind","text","evidenceIds");if(!string(candidate.get("kind")).equals("FACT"))throw new IllegalArgumentException();string(candidate.get("text"));if(!validIds(candidate.get("evidenceIds"),available))return rejected("SOURCE_REJECTED",now);}
            if(legacy&&!validIds(neutral.get("evidenceIds"),available))return rejected("SOURCE_REJECTED",now);
            for(var counter:negatives){fields(counter,"evidenceId","issue");if(!available.containsKey(string(counter.get("evidenceId"))))return rejected("SOURCE_REJECTED",now);if(!Set.of("NUMBER","NEGATION","TIME","CONDITION","INSUFFICIENT").contains(string(counter.get("issue"))))throw new IllegalArgumentException();}
            if(!negatives.isEmpty())return rejected("EVIDENCE_CONFLICT",now);
            if(!decision.equals("SHOW"))return rejected("EVIDENCE_INSUFFICIENT",now);
            var selected=positives.get(chosen);
            String text=string((legacy?neutral:selected).get("text"));
            if(text.isBlank()||text.codePointCount(0,text.length())>ConversateSessionService.HINT_TEXT_MAX||text.lines().count()>ConversateSessionService.HINT_LINE_MAX||!text.matches("(?s).*[가-힣].*")||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'))return rejected("CARD_LIMIT",now);
            if(legacy&&(!text.equals(string(selected.get("text")))||!selected.get("evidenceIds").equals(neutral.get("evidenceIds"))))return rejected("SOURCE_REJECTED",now);
            var cited=new ArrayList<ConversateCardPrompt.Evidence>();for(var id:selected.get("evidenceIds"))cited.add(available.get(id.textValue()));
            String support=cited.stream().map(ConversateCardPrompt.Evidence::text).reduce((a,b)->a+"\n"+b).orElse("");
            if(!quantities(text).equals(quantities(support)))return rejected("QUANTITY_REJECTED",now);
            if(negative(text)!=negative(support))return rejected("NEGATION_REJECTED",now);
            for(String condition:CONDITIONS)if(support.contains(condition)&&!text.contains(condition))return rejected("CONDITION_REJECTED",now);
            var sourceTokens=tokens(support);int cursor=0;
            for(String token:tokens(text)){while(cursor<sourceTokens.size()&&!sourceTokens.get(cursor).equals(token))cursor++;if(cursor==sourceTokens.size())return rejected("UNSUPPORTED_WORDING",now);cursor++;}
            return new Verified("GENERATED",new ConversateSessionService.Card("SHOW","FACT",text,cited.stream().map(ConversateCardPrompt.Evidence::sourceId).distinct().toList(),now+20_000));
        }catch(Exception malformed){return rejected("PARSE_FAILED",now);}
    }
    static Verified rejected(String reason,long now){return new Verified(reason,new ConversateSessionService.Card(reason.equals("CANCELLED")?"HOLD":"ASK","CLARIFICATION",reason.equals("CANCELLED")?"":"조건을 확인할 근거가 더 필요합니다.",List.of(),now+20_000));}
    private static void fields(JsonNode node,String...names){if(node==null||!node.isObject()||node.size()!=names.length)throw new IllegalArgumentException();for(String name:names)if(!node.has(name))throw new IllegalArgumentException();}
    private static String string(JsonNode node){if(node==null||!node.isTextual())throw new IllegalArgumentException();return node.textValue();}
    private static boolean validIds(JsonNode ids,Map<String,?> available){if(ids==null||!ids.isArray()||ids.isEmpty()||ids.size()>4)return false;var seen=new HashSet<String>();for(var id:ids)if(!id.isTextual()||!available.containsKey(id.textValue())||!seen.add(id.textValue()))return false;return true;}
    private static Set<String> quantities(String text){var found=new HashSet<String>();var m=QUANTITY.matcher(text);while(m.find())found.add(m.group().replaceAll("\\s+",""));return found;}
    private static boolean negative(String text){return text.matches("(?s).*(지 않|하지 않|불가|아니|아닙|제외|없).*");}
    private static List<String> tokens(String text){return Arrays.stream(text.replaceAll("[^\\p{L}\\p{N}%]+"," ").strip().split("\\s+")).filter(t->!t.isBlank()).toList();}
}
