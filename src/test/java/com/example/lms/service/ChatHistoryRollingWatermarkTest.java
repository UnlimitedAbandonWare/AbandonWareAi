package com.example.lms.service;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHistoryRollingWatermarkTest {
    private static final String PREFIX="⎔RSUM⎔";

    @AfterEach void clearTrace() { com.example.lms.search.TraceStore.clear(); }

    @Test
    void twoBoundedUpdatesProcessThirtyBacklogTurnsWithoutSkippingPastTheFirstPage() throws Exception {
        Fixture f=new Fixture();
        for(long id=12;id<=41;id++) f.add(id,"user","turn-"+id+".");
        f.service.updateRollingSummary(42L,41L);
        String first=f.saved.get(0);
        f.service.updateRollingSummary(42L,41L);
        String second=f.saved.get(f.saved.size()-1);
        assertAll(
                ()->assertEquals(34,watermark(first)),
                ()->assertEquals(41,watermark(second)),
                ()->assertEquals(List.of(10L,34L),f.afterIds),
                ()->assertEquals(List.of(24,24),f.pageSizes),
                ()->assertEquals(28,number(first,"turns")),
                ()->assertEquals(35,number(second,"turns")));
        String secondBody=second.substring(second.indexOf('\n')+1);
        for(long id=12;id<=41;id++) {
            String marker="User: turn-"+id+".";
            assertTrue(secondBody.contains(marker),"every bounded backlog turn must eventually be considered");
            assertEquals(secondBody.indexOf(marker),secondBody.lastIndexOf(marker),"processed turns must not duplicate");
        }
    }

    @Test
    void inspectedMetaOnlyPageAdvancesOnlyToItsLastRowThenReachesConversationBacklog() throws Exception {
        Fixture f=new Fixture();
        for(long id=12;id<=34;id++) f.add(id,id%2==0?"system":"user",id%2==0?"⎔TRACE⎔fixture":"  ");
        for(long id=35;id<=41;id++) f.add(id,"assistant","reply-"+id+".");
        f.service.updateRollingSummary(42L,41L);
        String first=f.saved.get(0);
        f.service.updateRollingSummary(42L,41L);
        String second=f.saved.get(f.saved.size()-1);
        assertAll(
                ()->assertEquals(34,watermark(first)),
                ()->assertEquals(5,number(first,"turns")),
                ()->assertEquals(41,watermark(second)),
                ()->assertEquals(12,number(second,"turns")),
                ()->assertEquals(List.of(10L,34L),f.afterIds));
        for(long id=35;id<=41;id++) assertTrue(second.contains("reply-"+id+"."));
        assertFalse(second.contains("⎔TRACE⎔"));
    }

    @Test
    void exactlyOneFullPhysicalPageStillReachesTheRequestedId() throws Exception {
        Fixture f=new Fixture();
        for(long id=12;id<=34;id++) f.add(id,"user","turn-"+id+".");
        f.service.updateRollingSummary(42L,34L);
        assertEquals(34,watermark(f.saved.get(0)));
        assertEquals(28,number(f.saved.get(0),"turns"));
        assertEquals(List.of(24),f.pageSizes);
    }

    @Test
    void requestedUpperBoundaryExcludesNewerRowsEvenWhenTheyFitThePage() throws Exception {
        Fixture f=new Fixture();
        for(long id=12;id<=30;id++) f.add(id,"user","turn-"+id+".");
        f.service.updateRollingSummary(42L,20L);
        String saved=f.saved.get(0);
        assertEquals(20,watermark(saved));
        assertEquals(14,number(saved,"turns"));
        assertTrue(saved.contains("turn-20."));
        assertFalse(saved.contains("turn-21."));
    }

    @Test
    void emptyEligibleDeltaCannotAdvanceTheWatermarkToAnUnobservedTarget() throws Exception {
        Fixture f=new Fixture();
        // The prior RSUM row is itself an inspected physical row, so first acknowledge it.
        f.service.updateRollingSummary(42L,11L);
        assertEquals(11,watermark(f.saved.get(0)));
        f.saved.clear();
        f.service.updateRollingSummary(42L,11L);
        assertTrue(f.saved.isEmpty(),"an empty eligible delta must not rewrite summary state");
        assertEquals(11,watermark(f.current.getContent()));
    }

    @Test
    void olderCompletionCannotMoveAnExistingWatermarkBackwards() throws Exception {
        Fixture f=new Fixture();
        f.service.updateRollingSummary(42L,5L);
        assertTrue(f.saved.isEmpty());
        assertEquals(10,watermark(f.current.getContent()));
    }

    private static long watermark(String content) throws Exception { return number(content,"lastMessageId"); }
    private static long number(String content,String key) throws Exception {
        String json=content.substring(PREFIX.length()).split("\n",2)[0];
        return new ObjectMapper().readTree(json).path(key).asLong();
    }

    private static final class Fixture {
        final ChatSession session=new ChatSession("watermark fixture");
        final List<ChatMessage> rows=new ArrayList<>();
        final List<String> saved=new ArrayList<>();
        final List<Long> afterIds=new ArrayList<>();
        final List<Integer> pageSizes=new ArrayList<>();
        final ChatMessageRepository messages=mock(ChatMessageRepository.class);
        final ChatSessionRepository sessions=mock(ChatSessionRepository.class);
        final ChatHistoryServiceImpl service;
        ChatMessage current;
        Fixture() {
            session.setId(42L);
            current=add(11L,"system",PREFIX+"{\"lastMessageId\":10,\"turns\":5}\nPrior fact.");
            service=new ChatHistoryServiceImpl(sessions,messages,mock(AdministratorRepository.class),new ObjectMapper(),mock(ClientOwnerKeyResolver.class));
            ReflectionTestUtils.setField(service,"rollingSummaryMaxChars",8_000);
            ReflectionTestUtils.setField(service,"rollingSummaryPromoteMinTurns",999);
            ReflectionTestUtils.setField(service,"rollingSummaryPromoteTokenThreshold",999);
            ReflectionTestUtils.setField(service,"rollingSummaryPromoteSentenceThreshold",999);
            ReflectionTestUtils.setField(service,"rollingSummaryAnchorCount",4);
            ReflectionTestUtils.setField(service,"rollingSummaryImportantSentenceCount",2);
            when(sessions.findById(42L)).thenReturn(Optional.of(session));
            when(messages.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(eq(42L),eq("system"),eq(PREFIX)))
                    .thenAnswer(inv->Optional.of(current));
            when(messages.findBySession_IdAndIdGreaterThanOrderByIdAsc(eq(42L),anyLong(),any(Pageable.class)))
                    .thenAnswer(inv->{
                        long after=inv.getArgument(1); Pageable page=inv.getArgument(2);
                        afterIds.add(after); pageSizes.add(page.getPageSize());
                        assertEquals(0,page.getPageNumber());
                        return rows.stream().filter(m->m.getId()>after).sorted(Comparator.comparing(ChatMessage::getId))
                                .skip(page.getOffset()).limit(page.getPageSize()).toList();
                    });
            when(messages.save(any(ChatMessage.class))).thenAnswer(inv->{
                ChatMessage value=inv.getArgument(0);
                value.setId(rows.stream().mapToLong(ChatMessage::getId).max().orElse(11)+1);
                rows.add(value); current=value; saved.add(value.getContent()); return value;
            });
        }
        ChatMessage add(long id,String role,String content) {
            ChatMessage row=new ChatMessage(session,role,content);row.setId(id);
            row.setCreatedAt(LocalDateTime.of(2026,1,1,0,0).plusSeconds(id));rows.add(row);return row;
        }
    }
}
