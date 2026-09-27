package com.example.lms.assist;

import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.example.lms.assist.ConversateSessionService.error;

@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
@Transactional(readOnly=true)
public class ConversatePreparedMaterials implements PreparedMaterialReader {
    private final ChatSessionRepository sessions;private final AttachmentService attachments;private final ClientOwnerKeyResolver owners;
    public ConversatePreparedMaterials(ChatSessionRepository sessions,AttachmentService attachments,ClientOwnerKeyResolver owners){this.sessions=sessions;this.attachments=attachments;this.owners=owners;}
    public List<Choice> choices(String username,String sessionId){
        try{var owner=authorize(username,sessionId);return attachments.findIdsBySession(sessionId,32,owner).stream().map(id->new Choice(id,attachments.find(id,owner).map(a->a.name()).orElse("준비 자료"))).toList();}
        catch(org.springframework.dao.DataAccessException unavailable){throw error(HttpStatus.SERVICE_UNAVAILABLE,"material_index_unavailable");}
    }
    public List<Material> read(String username,String sessionId,List<String> selectedIds){
        try{
        var owner=authorize(username,sessionId);
        if(selectedIds==null||selectedIds.isEmpty()||selectedIds.size()>8||selectedIds.stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9._:-]{1,128}")))throw error(HttpStatus.BAD_REQUEST,"invalid_material_selection");
        var ids=selectedIds.stream().distinct().toList();var allowed=new HashSet<>(attachments.findIdsBySession(sessionId,256,owner));
        if(!allowed.containsAll(ids))throw error(HttpStatus.FORBIDDEN,"material_denied");
        var documents=attachments.asDocumentsForSession(ids,sessionId,owner);var material=new ArrayList<Material>();
        for(var doc:documents){if(doc.text()==null||doc.text().isBlank())throw error(HttpStatus.UNPROCESSABLE_ENTITY,"material_parse_failed");String id=doc.metadata().getString("attachmentId");if(!ids.contains(id))throw error(HttpStatus.FORBIDDEN,"material_denied");material.add(new Material(id,doc.text()));}
        if(material.size()!=ids.size())throw error(HttpStatus.UNPROCESSABLE_ENTITY,"material_parse_failed");
        if(material.stream().mapToInt(m->m.text().length()).sum()>65536)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"prepared_material_limit");
        return List.copyOf(material);
        }catch(org.springframework.dao.DataAccessException unavailable){throw error(HttpStatus.SERVICE_UNAVAILABLE,"material_index_unavailable");}
    }
    private AttachmentOwnerIdentity authorize(String username,String sessionId){
        if(username==null||username.isBlank())throw error(HttpStatus.UNAUTHORIZED,"authentication_required");
        if(sessionId==null||!sessionId.matches("[1-9][0-9]{0,17}"))throw error(HttpStatus.BAD_REQUEST,"invalid_prepared_session");
        var session=sessions.findById(Long.parseLong(sessionId)).orElseThrow(()->error(HttpStatus.NOT_FOUND,"prepared_session_not_found"));
        if(session.getAdministrator()!=null){if(!username.equals(session.getAdministrator().getUsername()))throw error(HttpStatus.NOT_FOUND,"prepared_session_not_found");return AttachmentOwnerIdentity.forAdministrator(username);}
        String actual=owners.ownerKey();if(session.getOwnerKey()==null||!session.getOwnerKey().equals(actual))throw error(HttpStatus.NOT_FOUND,"prepared_session_not_found");return AttachmentOwnerIdentity.forAnonymous(actual);
    }
}
