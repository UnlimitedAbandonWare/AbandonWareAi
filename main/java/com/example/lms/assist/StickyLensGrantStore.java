package com.example.lms.assist;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Debug-only local bearer grants. No captions, prompts, cookies or session data are persisted. */
final class StickyLensGrantStore {
    private static final long TTL=30L*24*60*60*1000;
    private final Path path;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final SecureRandom random=new SecureRandom();
    record Grant(String token,String owner,long expiresAt) {
        @Override public String toString(){return "StickyLensGrant[redacted]";}
    }
    StickyLensGrantStore(String file){path=Path.of(file).toAbsolutePath().normalize();}
    Grant find(String token)throws IOException {return read().get(token);}
    synchronized Grant renew(String owner,String liveOwnedToken,long now)throws IOException {
        verifyPath();
        Path lock=path.resolveSibling(path.getFileName()+".lock");
        if(Files.isSymbolicLink(lock))throw new IOException("unsafe_grant_lock");
        try(var channel=FileChannel.open(lock,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.tryLock()){
            if(held==null)throw new IOException("grant_store_busy");
            var grants=read();
            grants.values().removeIf(g->g.expiresAt()<=now);
            Grant prior=grants.values().stream().filter(g->g.owner().equals(owner)).findFirst().orElse(null);
            String token=prior==null?liveOwnedToken:prior.token();
            // Only a live same-owner grant may migrate. Cached read authority alone cannot enroll a producer.
            if(token!=null&&grants.containsKey(token)&&!grants.get(token).owner().equals(owner))throw new IOException("grant_owner_conflict");
            if(token==null){do{byte[] bytes=new byte[32];random.nextBytes(bytes);token=HexFormat.of().formatHex(bytes);}while(grants.containsKey(token));}
            if(prior==null&&grants.size()>=256)throw new IOException("grant_store_full");
            var renewed=new Grant(token,owner,now+TTL);grants.put(token,renewed);
            write(grants);return renewed;
        }catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("grant_store_busy");}
    }
    private LinkedHashMap<String,Grant> read()throws IOException {
        verifyPath();var grants=new LinkedHashMap<String,Grant>();
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return grants;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>131072)throw new IOException("invalid_grant_store");
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>131072)throw new IOException("invalid_grant_store");
        var root=json.readTree(bytes);
        if(root==null||!root.isObject()||root.size()!=2||root.path("schemaVersion").asInt()!=1||!root.path("grants").isArray()||root.path("grants").size()>256)throw new IOException("invalid_grant_store");
        var owners=new HashSet<String>();
        for(var row:root.path("grants")){
            String token=row.path("token").asText(""),owner=row.path("owner").asText("");
            if(row.size()!=3||!token.matches("[a-f0-9]{64}")||!owner.matches("[a-f0-9]{64}")
                    ||!row.path("expiresAt").isIntegralNumber()||!row.path("expiresAt").canConvertToLong()
                    ||row.path("expiresAt").asLong()<=0||grants.containsKey(token)||!owners.add(owner))throw new IOException("invalid_grant_store");
            grants.put(token,new Grant(token,owner,row.path("expiresAt").asLong()));
        }
        return grants;
    }
    private void verifyPath()throws IOException {
        Path parent=path.getParent();
        if(parent==null||!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)||!parent.toRealPath().equals(parent)||Files.isSymbolicLink(path))throw new IOException("unsafe_grant_store");
    }
    private void write(Map<String,Grant> grants)throws IOException {
        verifyPath();Path parent=path.getParent();
        // Windows inherits the existing restricted .secrets directory ACL; POSIX gets 0600 before writing.
        var attributes=Files.getFileStore(parent).supportsFileAttributeView("posix")
                ?new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                :new java.nio.file.attribute.FileAttribute<?>[0];
        Path temporary=Files.createTempFile(parent,".display-lens-",".tmp",attributes);
        try{
            byte[] bytes=json.writeValueAsBytes(Map.of("schemaVersion",1,"grants",grants.values()));
            try(var out=FileChannel.open(temporary,WRITE,TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
            }
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
}
