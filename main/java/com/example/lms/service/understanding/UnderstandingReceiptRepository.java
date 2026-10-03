package com.example.lms.service.understanding;

import com.example.lms.jobs.JobService;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.*;

@Repository
public class UnderstandingReceiptRepository {
    private final JdbcTemplate jdbc;
    private final ChatMessageRepository messages;
    public UnderstandingReceiptRepository(DataSource dataSource, ChatMessageRepository messages) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.messages = messages;
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("understanding_receipt_transaction_required");
    }
    /** Called after source and job locks. Any matching hash still requires the whole tuple and USUM. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> findPersisted(DeferredUnderstandingTask task) {
        requireTransaction();
        var rows = jdbc.query("SELECT owner_namespace,session_id,channel,consent_epoch,user_message_id,user_revision,assistant_message_id,assistant_revision,kind,usum_message_id,result_sha256,receipt_state FROM awx_understanding_receipts WHERE effect_key=? FOR UPDATE",
                (rs, row) -> new ReceiptRow(List.of(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9)),
                        rs.getLong(10), rs.getString(11), rs.getString(12)), task.effectKey());
        if (rows.isEmpty()) return Optional.empty();
        ReceiptRow receipt = rows.get(0);
        if (!task.sourceTuple().equals(receipt.tuple()) || !"PERSISTED".equals(receipt.state()))
            throw new JobService.DerivedRejected();
        var usum = messages.findById(receipt.usumId()).orElseThrow(JobService.DerivedRejected::new);
        String prefix = UnderstandAndMemorizeInterceptor.USUM_META_PREFIX;
        if (usum.getSession() == null || !Objects.equals(usum.getSession().getId(), task.sessionId())
                || !"system".equals(usum.getRole()) || usum.getContent() == null || !usum.getContent().startsWith(prefix)
                || !org.apache.commons.codec.digest.DigestUtils.sha256Hex(usum.getContent().substring(prefix.length())).equals(receipt.resultHash()))
            throw new JobService.DerivedRejected();
        return Optional.of(receipt.usumId());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(DeferredUnderstandingTask task, JobService.DerivedClaim claim, long usumId, String summaryJson) {
        requireTransaction();
        if (usumId <= 0 || summaryJson == null || summaryJson.isBlank()) throw new IllegalArgumentException("understanding_receipt_missing_result");
        // Unique conflicts propagate and roll back T3. A separate recovery transaction may read the winner.
        jdbc.update("INSERT INTO awx_understanding_receipts(effect_key,owner_namespace,session_id,channel,consent_epoch,user_message_id,user_revision,assistant_message_id,assistant_revision,kind,original_run_id,job_task_id,usum_message_id,result_sha256,persisted_at,receipt_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PERSISTED')",
                task.effectKey(), task.ownerNamespace(), task.sessionId(), task.channel(), task.consentEpoch(),
                task.userMessageId(), task.userRevision(), task.assistantMessageId(), task.assistantRevision(), task.kind(),
                task.originalRunId(), claim.taskId(), usumId, org.apache.commons.codec.digest.DigestUtils.sha256Hex(summaryJson), System.currentTimeMillis());
    }
    private record ReceiptRow(List<Object> tuple, long usumId, String resultHash, String state) { }
}
