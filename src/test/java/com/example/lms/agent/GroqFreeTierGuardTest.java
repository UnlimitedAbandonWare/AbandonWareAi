package com.example.lms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroqFreeTierGuardTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    final String key="synthetic-key", model="whisper-large-v3-turbo";
    long now=Instant.parse("2026-09-17T00:00:00Z").toEpochMilli();
    final MockEnvironment env=new MockEnvironment();
    ObjectNode evidence;
    GroqFreeTierGuard guard() { return new GroqFreeTierGuard(env,json,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)); }
    void prepare()throws Exception {
        env.withProperty("groq.free-tier.evidence",dir.resolve("proof.json").toString())
           .withProperty("groq.free-tier.ledger",dir.resolve("quota.jsonl").toString())
           .withProperty("groq.free-tier.safety-margin","0");
        evidence=json.createObjectNode().put("plan","free").put("verifiedAtMs",now).put("expiresAtMs",now+86400000)
                .put("keySha256",GroqFreeTierGuard.keyHash(key)).put("organizationHash","a".repeat(64)).put("coordination","shared_ledger").put("speechVerified",true);
        evidence.put("ledgerPathSha256",GroqFreeTierGuard.ledgerPathHash(dir.resolve("quota.jsonl").toString()));
        var limits=evidence.putObject("limits");
        limits.putObject(model).put("rpm",20).put("rpd",2000).put("ash",7200).put("asd",28800);
        limits.putObject("openai/gpt-oss-20b").put("rpm",30).put("rpd",1000).put("tpm",8000).put("tpd",200000);
        save();
    }
    void save()throws Exception { Files.writeString(dir.resolve("proof.json"),json.writeValueAsString(evidence)); }
    @Test void paidMissingStaleAndWrongKeyNeverAdmitEvenWithCostLimitsOff()throws Exception {
        prepare();env.withProperty("conversate.cost.enforce-limits","false");
        assertTrue(guard().eligible(model,key));assertFalse(guard().eligible(model,"another-key"));
        evidence.put("plan","developer");save();assertFalse(guard().eligible(model,key));
        evidence.put("plan","free");save();now+=86400001;assertFalse(guard().eligible(model,key));
        Files.delete(dir.resolve("proof.json"));assertFalse(guard().eligible(model,key));
        assertFalse(Files.exists(dir.resolve("quota.jsonl")));
    }
    @Test void independentGuardsShareRequestCountsAndSafetyMargin()throws Exception {
        prepare();env.withProperty("groq.free-tier.safety-margin","0.1");
        for(int i=0;i<18;i++)guard().reserve(model,key,0,5);
        assertEquals("groq_rpm_exhausted",assertThrows(Exception.class,()->guard().reserve(model,key,0,5)).getMessage());
        now+=60000;assertNotNull(guard().reserve(model,key,0,5));
    }
    @Test void accountRequestAudioAndTokenCapsAreIndependent()throws Exception {
        prepare();((ObjectNode)evidence.path("limits").path(model)).put("ash",20);save();
        guard().reserve(model,key,0,1);guard().reserve(model,key,0,1);
        assertEquals("groq_ash_exhausted",assertThrows(Exception.class,()->guard().reserve(model,key,0,1)).getMessage());
        guard().reserve("openai/gpt-oss-20b",key,7900,0);
        assertEquals("groq_tpm_exhausted",assertThrows(Exception.class,()->guard().reserve("openai/gpt-oss-20b",key,101,0)).getMessage());
        now+=60000;assertNotNull(guard().reserve("openai/gpt-oss-20b",key,101,0));
    }
    @Test void dailyRequestAudioAndTokenCapsDoNotResetAtMinuteBoundary()throws Exception {
        prepare();var speech=(ObjectNode)evidence.path("limits").path(model);speech.put("rpd",1);save();
        guard().reserve(model,key,0,15);now+=60000;
        assertEquals("groq_rpd_exhausted",assertThrows(Exception.class,()->guard().reserve(model,key,0,15)).getMessage());
        speech.put("rpd",2000).put("asd",15);save();
        assertEquals("groq_asd_exhausted",assertThrows(Exception.class,()->guard().reserve(model,key,0,15)).getMessage());
        ((ObjectNode)evidence.path("limits").path("openai/gpt-oss-20b")).put("tpd",500);save();
        guard().reserve("openai/gpt-oss-20b",key,500,0);now+=60000;
        assertEquals("groq_tpd_exhausted",assertThrows(Exception.class,()->guard().reserve("openai/gpt-oss-20b",key,1,0)).getMessage());
    }
    @Test void headersClampAcrossInstancesWithoutRefundingOtherInFlightReservations()throws Exception {
        prepare();var first=guard().reserve(model,key,0,5);guard().reserve(model,key,0,5);
        guard().observe(first,200,Map.of("X-RateLimit-Remaining-Requests",List.of("1"),"x-ratelimit-reset-requests",List.of("43.2s")));
        assertEquals("groq_header_requests_exhausted",assertThrows(Exception.class,()->guard().reserve(model,key,0,5)).getMessage());
        now+=43201;assertNotNull(guard().reserve(model,key,0,5));
    }
    @Test void authLatchAndRetryAfterSurviveNewGuardInstances()throws Exception {
        prepare();var first=guard().reserve(model,key,0,5);guard().observe(first,429,Map.of("Retry-After",List.of("120")));
        now+=60001;assertEquals("groq_retry_after",assertThrows(Exception.class,()->guard().reserve(model,key,0,5)).getMessage());
        now+=60000;var next=guard().reserve(model,key,0,5);guard().observe(next,403,Map.of());
        now+=3600000;assertEquals("groq_auth_latched",assertThrows(Exception.class,()->guard().reserve("openai/gpt-oss-20b",key,100,0)).getMessage());
    }
    @Test void badLedgerAndClockRollbackFailClosed()throws Exception {
        prepare();guard().reserve(model,key,0,5);long original=now;now--;
        evidence.put("verifiedAtMs",now-1);save();assertThrows(Exception.class,()->guard().reserve(model,key,0,5));now=original;
        Files.writeString(dir.resolve("quota.jsonl"),"{torn");assertThrows(Exception.class,()->guard().reserve(model,key,0,5));
        Files.writeString(dir.resolve("quota.jsonl"),"");assertThrows(Exception.class,()->guard().reserve(model,key,0,5));
    }
    @Test void deniedFirstReservationDoesNotPoisonValidFutureAdmission()throws Exception {
        prepare();assertThrows(Exception.class,()->guard().reserve("openai/gpt-oss-20b",key,8001,0));
        assertNotNull(guard().reserve("openai/gpt-oss-20b",key,100,0));
    }
    @Test void validatesRetryDateAndAudioBoundary()throws Exception {
        prepare();assertThrows(Exception.class,()->guard().reserve(model,key,0,17));
        assertNotNull(guard().reserve(model,key,0,15));
        assertEquals(60000,GroqFreeTierGuard.retryAfter(Map.of(),now));
        String future=java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now+120000).atZone(ZoneOffset.UTC));
        assertEquals(120000,GroqFreeTierGuard.retryAfter(Map.of("retry-after",List.of(future)),now));
    }
    @Test void copiedProofCannotSilentlyStartADifferentDeviceLedger()throws Exception {
        prepare();env.withProperty("groq.free-tier.ledger",dir.resolve("another-ledger.jsonl").toString());
        assertFalse(guard().eligible(model,key));assertThrows(Exception.class,()->guard().reserve(model,key,0,5));
        assertFalse(Files.exists(dir.resolve("another-ledger.jsonl")));
    }
}
