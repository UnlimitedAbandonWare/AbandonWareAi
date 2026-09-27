package com.example.lms.service.chat;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** Shared routing metadata, never an execution queue. Expired owners are not retried. */
public final class ChatRunOwnerDirectory {
    public static final long LEASE_MILLIS = 30_000;
    public record Owner(long sessionId, String runToken, String instanceId, String bootId,
                        String state, long leaseUntil, Long replayUntil) { }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final String instanceId;
    private final String bootId = UUID.randomUUID().toString();

    public ChatRunOwnerDirectory(DataSource source, String instanceId) {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("chat_cluster_instance_id_invalid");
        this.instanceId = instanceId;
        db = new JdbcTemplate(source); db.setQueryTimeout(2);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setTimeout(3);
    }
    private long now() { return Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class)).getTime(); }
    private static Timestamp stamp(long value) { return new Timestamp(value); }
    public static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"run_owner_unavailable"); }
    private <T> T guarded(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | org.springframework.transaction.TransactionException failed) { throw unavailable(); }
    }

    /** Persist the global claim before installing a local run or starting any producer. */
    public void claim(Long sessionId, String token) {
        if (sessionId == null || token == null) throw new IllegalArgumentException("run_identity_required");
        guarded(() -> {
            // Duplicate insert is outside the transaction so an expected race cannot poison it.
            try { db.update("INSERT INTO awx_chat_run_slots(session_id,state) VALUES (?,'EMPTY')",sessionId); }
            catch (DuplicateKeyException alreadyExists) { /* Lock the existing slot below. */ }
            return tx.execute(status -> {
                var slot = db.queryForMap("SELECT run_token,state FROM awx_chat_run_slots WHERE session_id=? FOR UPDATE",sessionId);
                long time=now();
                if ("DELETING".equals(slot.get("state")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"session_deleting");
                if ("ACTIVE".equals(slot.get("state"))) {
                    var previous=findRow(sessionId, (String)slot.get("run_token"));
                    if (previous.isEmpty() || previous.get().leaseUntil() <= time) throw unavailable();
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"run_active");
                }
                db.update("INSERT INTO awx_chat_run_owners(run_token,session_id,instance_id,boot_id,state,lease_until) VALUES (?,?,?,?,'ACTIVE',?)",
                        token,sessionId,instanceId,bootId,stamp(time+LEASE_MILLIS));
                db.update("UPDATE awx_chat_run_slots SET run_token=?,state='ACTIVE' WHERE session_id=?",token,sessionId);
                return null;
            });
        });
    }
    private Optional<Owner> findRow(Long session, String token) {
        return db.query("SELECT * FROM awx_chat_run_owners WHERE session_id=? AND run_token=?", (row,n) -> {
            var replay=row.getTimestamp("replay_until");
            return new Owner(row.getLong("session_id"),row.getString("run_token"),row.getString("instance_id"),
                    row.getString("boot_id"),row.getString("state"),row.getTimestamp("lease_until").getTime(),replay==null?null:replay.getTime());
        },session,token).stream().findFirst();
    }
    public Optional<Owner> find(Long session, String token) {
        if (session==null || token==null || token.length()>36) return Optional.empty();
        return guarded(() -> {
            long time=now();
            return findRow(session,token).filter(o -> o.replayUntil()==null || o.replayUntil()>time);
        });
    }
    public Optional<String> currentToken(Long session) {
        if (session==null) return Optional.empty();
        return guarded(() -> db.query("SELECT run_token FROM awx_chat_run_slots WHERE session_id=?",(r,n)->r.getString(1),session)
                .stream().filter(Objects::nonNull).findFirst());
    }
    public boolean isLocal(Owner owner) { return instanceId.equals(owner.instanceId()) && bootId.equals(owner.bootId()); }
    /** Fence replacement before the caller cancels the owner and deletes durable history. */
    public Optional<Owner> freezeForDeletion(Long session) {
        return guarded(() -> {
            try { db.update("INSERT INTO awx_chat_run_slots(session_id,state) VALUES (?,'EMPTY')",session); }
            catch (DuplicateKeyException exists) { /* Existing session slot. */ }
            return tx.execute(status -> {
                var slot=db.queryForMap("SELECT run_token,state FROM awx_chat_run_slots WHERE session_id=? FOR UPDATE",session);
                var found=findRow(session,(String)slot.get("run_token"));
                if(found.isPresent() && "ACTIVE".equals(found.get().state()) && found.get().leaseUntil()<=now())throw unavailable();
                db.update("UPDATE awx_chat_run_slots SET state='DELETING' WHERE session_id=?",session);
                return found.filter(o -> "ACTIVE".equals(o.state()) || (o.replayUntil()!=null && o.replayUntil()>now()));
            });
        });
    }
    public boolean isAvailable(Owner owner) {
        return guarded(() -> "ACTIVE".equals(owner.state()) ? owner.leaseUntil()>now()
                : owner.replayUntil()!=null && owner.replayUntil()>now());
    }
    /** Renew only known live local tokens. Boot fencing prevents process-name reuse. */
    public Set<String> renew(Set<String> tokens) {
        if(tokens.isEmpty())return Set.of();
        return guarded(() -> {
            long time=now();var renewed=new HashSet<String>();
            for(String token:tokens) {
                if(db.update("UPDATE awx_chat_run_owners SET lease_until=? WHERE run_token=? AND instance_id=? AND boot_id=? AND state='ACTIVE' AND lease_until>?",
                        stamp(time+LEASE_MILLIS),token,instanceId,bootId,stamp(time))==1) renewed.add(token);
            }
            return Set.copyOf(renewed);
        });
    }
    /** Slot release and replay retention are atomic, and only a still-leased owner may finish. */
    public boolean finish(Long session, String token, int replaySeconds) {
        return guarded(() -> Boolean.TRUE.equals(tx.execute(status -> {
            var slots=db.queryForList("SELECT run_token FROM awx_chat_run_slots WHERE session_id=? FOR UPDATE",session);
            if(slots.isEmpty() || !token.equals(slots.get(0).get("run_token")))return false;
            long time=now();
            int changed=db.update("UPDATE awx_chat_run_owners SET state='TERMINAL',replay_until=? WHERE session_id=? AND run_token=? AND instance_id=? AND boot_id=? AND state='ACTIVE' AND lease_until>?",
                    stamp(time+Math.max(0,replaySeconds)*1000L),session,token,instanceId,bootId,stamp(time));
            if(changed==1)db.update("UPDATE awx_chat_run_slots SET state=CASE WHEN state='DELETING' THEN 'DELETING' ELSE 'TERMINAL' END WHERE session_id=? AND run_token=?",session,token);
            return changed==1;
        })));
    }
    public void prune() {
        guarded(() -> db.update("DELETE FROM awx_chat_run_owners WHERE state='TERMINAL' AND replay_until<=?",stamp(now())));
        // An expired ACTIVE row is an uncertainty tombstone, not completed-result garbage.
    }
}
