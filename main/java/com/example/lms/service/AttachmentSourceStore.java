package com.example.lms.service;

import com.example.lms.domain.AttachmentSource;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.repository.AttachmentSourceRepository;
import com.example.lms.service.rag.graph.GeneralGraphScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Function;

/** Uses the application transaction manager, including the graph authority's session-row lock. */
@Service
public class AttachmentSourceStore {
    private final AttachmentSourceRepository rows;
    private final TransactionTemplate transactions;
    public AttachmentSourceStore(AttachmentSourceRepository rows,PlatformTransactionManager manager){
        this.rows=rows;this.transactions=new TransactionTemplate(manager);
    }
    public record Snapshot(AttachmentDto dto,String ownerNamespace,String sessionId,String contentSha256,
            long sourceRevision,String parserVersion,long retainedAt,long expiresAt,long consentEpoch,
            String unitsJson,String textState,String graphState,String vectorState,String failureReason) {
        @Override public String toString(){return "AttachmentSourceSnapshot[redacted]";}
    }
    private static Snapshot snapshot(AttachmentSource row){
        return new Snapshot(new AttachmentDto(row.getId(),row.getOriginalName(),row.getSizeBytes(),row.getContentType(),row.getStorageLocator()),
            row.getOwnerNamespace(),row.getSessionId(),row.getContentSha256(),row.getSourceRevision(),row.getParserVersion(),
            row.getRetainedAt(),row.getExpiresAt(),row.getConsentEpoch(),row.getUnitsJson(),row.getTextState(),
            row.getGraphState(),row.getVectorState(),row.getFailureReason());
    }
    private static boolean live(AttachmentSource row){
        return row!=null&&!row.isTombstone()&&row.getExpiresAt()>System.currentTimeMillis();
    }
    public void create(AttachmentDto dto,String owner,String session,String digest,long retainedAt,long expiresAt){
        if(owner==null||!owner.matches("[a-f0-9]{64}")||digest==null||!digest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("attachment_source_identity_missing");
        transactions.executeWithoutResult(tx->{
            if(rows.existsById(dto.id()))throw new IllegalStateException("attachment_source_exists");
            var row=new AttachmentSource();row.setId(dto.id());row.setOwnerNamespace(owner);row.setSessionId(session);
            row.setOriginalName(dto.name());row.setContentType(dto.contentType());row.setStorageLocator(dto.url());
            row.setSizeBytes(dto.size());row.setContentSha256(digest);row.setRetainedAt(retainedAt);row.setExpiresAt(expiresAt);
            rows.saveAndFlush(row);
        });
    }
    public Optional<Snapshot> find(String id){
        if(id==null)return Optional.empty();
        return transactions.execute(tx->rows.findById(id).filter(AttachmentSourceStore::live).map(AttachmentSourceStore::snapshot));
    }
    public List<Snapshot> forSession(String session){
        return transactions.execute(tx->rows.findTop256BySessionIdAndTombstoneFalseOrderByRetainedAtAsc(session).stream()
            .filter(AttachmentSourceStore::live).map(AttachmentSourceStore::snapshot).toList());
    }
    public List<String> expiredIds(long now){
        return transactions.execute(tx->rows.findTop256ByTombstoneFalseAndExpiresAtLessThanEqual(now).stream().map(AttachmentSource::getId).toList());
    }
    public Optional<Snapshot> includingExpired(String id){
        return transactions.execute(tx->rows.findById(id).filter(r->!r.isTombstone()).map(AttachmentSourceStore::snapshot));
    }
    public Optional<Snapshot> deleted(String id){
        return transactions.execute(tx->rows.findById(id).filter(AttachmentSource::isTombstone).map(AttachmentSourceStore::snapshot));
    }
    public boolean bind(String id,String owner,String session){
        return Boolean.TRUE.equals(transactions.execute(tx->{
            var row=rows.findForUpdate(id).orElse(null);
            if(!live(row)||!row.getOwnerNamespace().equals(owner)||session==null||session.isBlank()
                    ||row.getSessionId()!=null&&!row.getSessionId().equals(session))return false;
            row.setSessionId(session);rows.saveAndFlush(row);return true;
        }));
    }
    public void tombstone(String id){
        transactions.executeWithoutResult(tx->rows.findForUpdate(id).ifPresent(row->{
            if(!row.isTombstone()){
                row.setTombstone(true);row.setSourceRevision(Math.addExact(row.getSourceRevision(),1));
                row.setConsentEpoch(0);row.setUnitsJson(null);row.setTextState("DELETED");
                row.setGraphState("INVALIDATED");row.setVectorState("INVALIDATED");rows.saveAndFlush(row);
            }
        }));
    }
    /** A worker must present the exact revision it read; deletion and newer extractions win. */
    public Optional<Snapshot> recordText(String id,long expectedRevision,String digest,String parser,String unitsJson,String state){
        if(unitsJson==null||unitsJson.length()>1_048_576)throw new IllegalArgumentException("attachment_units_budget");
        return transactions.execute(tx->{
            var row=rows.findForUpdate(id).orElse(null);
            if(!live(row)||row.getSourceRevision()!=expectedRevision||!row.getContentSha256().equals(digest))return Optional.empty();
            if(!Objects.equals(row.getUnitsJson(),unitsJson)||!Objects.equals(row.getParserVersion(),parser)){
                row.setSourceRevision(Math.addExact(row.getSourceRevision(),1));row.setConsentEpoch(0);
                row.setUnitsJson(unitsJson);row.setParserVersion(parser);row.setTextState(state);
                row.setGraphState("NOT_INDEXED");row.setVectorState("NOT_INDEXED");row.setFailureReason("");
                rows.saveAndFlush(row);
            }
            return Optional.of(snapshot(row));
        });
    }
    public boolean grant(GeneralGraphScope scope,String id,long revision){
        return Boolean.TRUE.equals(transactions.execute(tx->{
            var row=rows.findForUpdate(id).orElse(null);
            if(!matches(scope,row)||row.getSourceRevision()!=revision||row.getUnitsJson()==null)return false;
            row.setConsentEpoch(scope.consentEpoch());rows.saveAndFlush(row);return true;
        }));
    }
    private static boolean matches(GeneralGraphScope scope,AttachmentSource row){
        return live(row)&&scope!=null&&scope.memoryEnabled()&&scope.ownerNamespace().equals(row.getOwnerNamespace())
            &&scope.matchesSession(row.getSessionId())&&scope.channel().equals(row.getChannel());
    }
    public <T> Optional<T> withCurrent(GeneralGraphScope scope,String id,long revision,Function<Snapshot,T> action){
        return transactions.execute(tx->{
            var row=rows.findForUpdate(id).orElse(null);
            if(!matches(scope,row)||row.getConsentEpoch()!=scope.consentEpoch()
                    ||row.getSourceRevision()!=revision||row.getUnitsJson()==null)return Optional.empty();
            return Optional.ofNullable(action.apply(snapshot(row)));
        });
    }
    public void recordIndex(String id,long revision,String graph,String vector,String reason){
        transactions.executeWithoutResult(tx->rows.findForUpdate(id).ifPresent(row->{
            if(live(row)&&row.getSourceRevision()==revision){
                row.setGraphState(graph);row.setVectorState(vector);row.setFailureReason(reason);rows.saveAndFlush(row);
            }
        }));
    }
}
