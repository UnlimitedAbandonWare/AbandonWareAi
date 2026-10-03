package com.example.lms.routing;

import com.example.lms.domain.ConfigurationSetting;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

@Service
public class RoutingSettingsService {
    public static final String KEY="CHAT_ROUTING_PROFILE_V1";
    public record State(long profileRevision,String profileHash,RoutingProfile profile) {}
    public static final class Conflict extends RuntimeException { public Conflict(){super("revision_conflict");} }
    @PersistenceContext private EntityManager entityManager;
    private final PlatformTransactionManager manager;
    public RoutingSettingsService(PlatformTransactionManager manager){this.manager=manager;}
    private TransactionTemplate transaction(boolean readOnly){
        var tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(readOnly);tx.setTimeout(1);return tx;
    }
    public State read(){return transaction(true).execute(status->state(entityManager.find(ConfigurationSetting.class,KEY)));}
    private State state(ConfigurationSetting row){
        if(row==null)return new State(0,null,RoutingProfile.empty());
        var profile=RoutingProfile.parse(row.getSettingValue());
        return new State(profile.revision(),hash(profile),profile);
    }
    public State save(RoutingProfile requested,long revision,String expectedHash){
        // Validate even callers that did not come through the MVC boundary.
        RoutingProfile valid=RoutingProfile.parse(requested.json());
        final State committed;
        try {
            committed=transaction(false).execute(status->{
                var row=entityManager.find(ConfigurationSetting.class,KEY,LockModeType.PESSIMISTIC_WRITE,
                        Map.of("jakarta.persistence.lock.timeout",1000));
                var current=state(row);
                if(current.profileRevision()!=revision || !Objects.equals(current.profileHash(),expectedHash))throw new Conflict();
                var next=valid.atRevision(Math.addExact(revision,1));
                if(row==null)entityManager.persist(new ConfigurationSetting(KEY,next.json()));
                else row.setSettingValue(next.json());
                entityManager.flush();
                return new State(next.revision(),hash(next),next);
            });
        } catch(RuntimeException e){
            if(e instanceof Conflict)throw e;
            // Only a unique-key race is a revision conflict. Other DB failures remain unavailable.
            for(Throwable cause=e;cause!=null;cause=cause.getCause())
                if(cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()))throw new Conflict();
            throw e;
        }
        State observed=read();
        if(committed==null || !committed.equals(observed))throw new Conflict();
        return observed;
    }
    private static String hash(RoutingProfile profile){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(profile.json().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("routing_hash_unavailable");}
    }
}
