package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateSttAccountsTest {
    final ObjectMapper json=new ObjectMapper();
    static final String PROJECT="11111111-1111-1111-1111-111111111111";
    @Test void disabledDoesNotCallAndMissingKeysNeverBecomeZeroBalance(){
        var count=new AtomicInteger();
        for(boolean enabled:new boolean[]{false,true}){
            var a=new ConversateSttAccounts(enabled,"",PROJECT,"",Clock.systemUTC(),(u,h)->{count.incrementAndGet();return null;});
            a.refreshIfDue();assertFalse(a.exhausted("deepgram"));assertFalse(a.exhausted("soniox"));
        }assertEquals(0,count.get());
    }
    @Test void positiveBalanceNeverClaimsFreeCreditAndIdentifiersAreNotProjected()throws Exception{
        var view=ConversateSttAccounts.parseBalance(json.readTree("{\"balances\":[{\"amount\":12.5,\"units\":\"USD\",\"balance_id\":\"PRIVATE\"}]}"));
        assertEquals("available",view.get("funds"));assertEquals("not_distinguished_by_balance_api",view.get("freeCredit"));
        assertFalse(view.toString().contains("12.5"));assertFalse(view.toString().contains("PRIVATE"));
        assertThrows(Exception.class,()->ConversateSttAccounts.parseBalance(json.readTree("{\"balances\":[]}")));
    }
    @Test void freshZeroBlocksButStaleAndFailedReadsStayUnknownAndFailuresAreCached(){
        var clock=new MutableClock();var count=new AtomicInteger();
        var a=new ConversateSttAccounts(true,"synthetic",PROJECT,"",clock,(u,h)->{
            assertEquals("api.deepgram.com",u.getHost());assertEquals("https",u.getScheme());assertTrue(u.getPath().endsWith("/balances"));
            if(count.incrementAndGet()>1)throw new java.io.IOException("PRIVATE_RESPONSE");
            return json.readTree("{\"balances\":[{\"amount\":0,\"units\":\"USD\"}]}");
        });
        a.refreshIfDue();assertTrue(a.exhausted("deepgram"));a.refreshIfDue();assertEquals(1,count.get());
        clock.now=clock.now.plusSeconds(301);assertFalse(a.exhausted("deepgram"));
        a.refreshIfDue();assertFalse(a.exhausted("deepgram"));a.refreshIfDue();assertEquals(2,count.get());
        assertFalse(a.diagnostics().toString().contains("PRIVATE_RESPONSE"));
    }
    @Test void sonioxWholeDayCountsDoNotBecomeRemainingCredit()throws Exception{
        var a=new ConversateSttAccounts(true,"","","synthetic",new MutableClock(),(u,h)->{
            assertEquals("api.soniox.com",u.getHost());assertEquals("/v1/usage/summary",u.getPath());
            assertTrue(u.getQuery().contains("end_time=2026-09-15T00:00:00Z"));
            return json.readTree("{\"total\":{\"total_num_requests\":2,\"total_input_audio_duration_ms\":1500,\"total_cost_usd\":\"0.001\"},\"models\":[]}");
        });a.refreshIfDue();assertFalse(a.exhausted("soniox"));
        assertTrue(a.diagnostics().toString().contains("project_all_models_completed_utc_days"));
        assertFalse(a.diagnostics().toString().contains("0.001"));
        assertThrows(Exception.class,()->ConversateSttAccounts.parseUsage(json.readTree("{\"total\":{\"total_num_requests\":-1}}")));
    }
    static class MutableClock extends Clock{
        Instant now=Instant.parse("2026-09-15T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
    }
}
