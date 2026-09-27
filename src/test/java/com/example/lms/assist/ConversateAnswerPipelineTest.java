package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversateAnswerPipelineTest {
    @Test void stageCountsDistinguishSkippedZeroAndActualResultsWithoutContent() {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var pipeline=new ConversateAnswerPipeline();
        var docs=java.util.List.of(new PreparedMaterialReader.Material("doc","보증 기간은 2년입니다."));
        var missing=json.valueToTree(pipeline.answer("보증 기간은?",java.util.List.of(),1000));
        assertTrue(missing.has("stages"));assertTrue(missing.path("stages").path("retrieved").isNull());
        var empty=json.valueToTree(pipeline.answer("완전히별개질문은?",docs,1000));
        assertEquals("NO_MATCH",empty.path("reason").asText());assertEquals(0,empty.path("stages").path("retrieved").asInt(-1));assertTrue(empty.path("stages").path("reranked").isNull());
        var hit=json.valueToTree(pipeline.answer("보증 기간은?",docs,1000)).path("stages");
        assertEquals(1,hit.path("indexed").asInt(-1));assertEquals(1,hit.path("retrieved").asInt(-1));assertEquals(1,hit.path("reranked").asInt(-1));assertEquals(1,hit.path("verified").asInt(-1));
        for(String key:java.util.List.of("retrievalMs","rerankMs","verificationMs"))assertTrue(hit.path(key).isNumber());
        assertFalse(hit.toString().contains("보증"));assertFalse(hit.toString().contains("doc"));
    }
    @Test void conceptsKeepActualEvidenceAndFollowupsRequireVolatileContext(){
        var docs=List.of(new PreparedMaterialReader.Material("terms","SLA는 서비스 수준 협약을 뜻합니다."));
        var concept=pipeline.answer("SLA의 뜻은 무엇인가요?",docs,1000);
        assertEquals("CONCEPT",concept.card().kind());assertEquals(List.of("terms"),concept.card().sourceIds());
        var followup=pipeline.answerWithContext("그 뜻은 무엇인가요?",List.of("SLA에 대해 이야기하고 있습니다."),docs,1000);
        assertEquals("CONCEPT",followup.card().kind());assertEquals(List.of("terms"),followup.card().sourceIds());
        assertEquals("CONTEXT_REQUIRED",pipeline.answerWithContext("그 뜻은 무엇인가요?",List.of(),docs,1000).reason());
    }
    @Test void suggestionUsesOneBoundedLocalChoiceAndNeverFabricatesSources(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var local=new ConversateLocalCardGenerator((messages,schema)->{calls.incrementAndGet();return "{\"choice\":1}";},1000);
        var result=new ConversateAnswerPipeline(local).answerWithContext("다음 질문을 제안해 주세요",List.of("조건을 설명하고 있습니다."),List.of(),1000);
        assertEquals("SUGGESTION",result.card().kind());assertTrue(result.card().text().startsWith("제안:"));
        assertTrue(result.card().sourceIds().isEmpty());assertEquals(1,result.generationAttempts());assertEquals(1,calls.get());
        assertEquals(0,result.searchAttempts());
        var invented=new ConversateLocalCardGenerator((messages,schema)->"{\"choice\":1,\"text\":\"이 제품은 무료입니다\"}",1000);
        assertEquals("PARSE_FAILED",new ConversateAnswerPipeline(invented).answer("다음 질문은?",List.of(),1000).reason());
    }
    private final ConversateAnswerPipeline pipeline=new ConversateAnswerPipeline();
    @Test void preparedKoreanAnswerUsesRetrievedSourceAndKeepsNegationAndUnits(){
        var result=pipeline.answer("보증 기간은 얼마인가요?",List.of(new PreparedMaterialReader.Material("doc-1","보증 기간은 2년입니다.\n침수 손상은 보증하지 않습니다.")),1000);
        assertEquals("MATCH",result.reason());assertEquals("SHOW",result.card().decision());
        assertEquals(1,result.searchAttempts());assertEquals(0,result.queryRefinements());
        assertEquals(List.of("doc-1"),result.card().sourceIds());assertTrue(result.card().text().contains("2년"));
        var negative=pipeline.answer("침수 손상은 보증하나요?",List.of(new PreparedMaterialReader.Material("doc-1","침수 손상은 보증하지 않습니다.")),1000);
        assertTrue(negative.card().text().contains("보증하지 않습니다"));assertTrue(negative.card().text().length()<=120);
    }
    @Test void noMatchNumericContradictionAndTooLongEvidenceNeverFabricateOrTruncate(){
        assertEquals("NO_MATCH",pipeline.answer("배송 날짜는 언제인가요?",List.of(new PreparedMaterialReader.Material("doc","보증 기간은 2년입니다.")),1000).reason());
        var conflict=pipeline.answer("보증 기간은 3년인가요?",List.of(new PreparedMaterialReader.Material("doc","보증 기간은 2년입니다.")),1000);
        assertEquals("ASK",conflict.card().decision());assertTrue(conflict.card().sourceIds().isEmpty());
        String longSentence="보증 조건은 "+"매우 긴 조건 ".repeat(30)+"에 해당하지 않습니다.";
        assertEquals("ASK",pipeline.answer("보증 조건은 무엇인가요?",List.of(new PreparedMaterialReader.Material("doc",longSentence)),1000).card().decision());
    }
    @Test void differingRelevantAmountsRequireClarification(){
        var result=pipeline.answer("가입 비용은 얼마인가요?",List.of(new PreparedMaterialReader.Material("a","가입 비용은 30만원입니다."),new PreparedMaterialReader.Material("b","가입 비용은 50만원입니다.")),1000);
        assertEquals("EVIDENCE_CONFLICT",result.reason());assertEquals("ASK",result.card().decision());
    }
    @Test void trueNoMatchCanRefineOneCompoundWithoutAddingGenericFacets(){
        var docs=List.of(new PreparedMaterialReader.Material("doc","보증 기간은 2년입니다."));
        var result=pipeline.answer("보증기간은 얼마인가요?",docs,1000);
        assertEquals("MATCH_REFINED",result.reason());assertEquals("보증 기간은 2년입니다.",result.card().text());assertEquals(List.of("doc"),result.card().sourceIds());
        assertEquals(2,result.searchAttempts());assertEquals(1,result.queryRefinements());assertEquals(0,result.generationAttempts());
        assertEquals("NO_MATCH",pipeline.answer("배송일자는 언제인가요?",List.of(new PreparedMaterialReader.Material("unrelated","일반 사용법 가이드 요약입니다.")),1000).reason());
    }
    @Test void refinementCannotDropNumbersNegationOrSelectAnAmbiguousSegmentation(){
        var number=pipeline.answer("보증기간은 3년인가요?",List.of(new PreparedMaterialReader.Material("doc","보증 기간은 2년입니다.")),1000);
        assertEquals("NUMBER_MISMATCH",number.reason());assertEquals("ASK",number.card().decision());
        assertEquals(2,number.searchAttempts());assertEquals(1,number.queryRefinements());
        var negative=pipeline.answer("수리불가 여부는?",List.of(new PreparedMaterialReader.Material("doc","수리 불가 대상은 침수 제품입니다.")),1000);
        assertEquals("MATCH_REFINED",negative.reason());assertTrue(negative.card().text().contains("불가"));
        assertEquals("NO_MATCH",pipeline.answer("가나다라마바사는?",List.of(new PreparedMaterialReader.Material("a","가나 다라마바사"),new PreparedMaterialReader.Material("b","가나다 라마바사")),1000).reason());
    }
}
