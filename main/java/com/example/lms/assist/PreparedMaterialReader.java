package com.example.lms.assist;
import java.util.List;
import org.springframework.http.HttpStatus;
import static com.example.lms.assist.ConversateSessionService.error;

/** Reads explicitly selected, previously retained documents. Never ingests a live utterance. */
public interface PreparedMaterialReader {
    record Material(String sourceId,String text){
        public Material {if(sourceId==null||!sourceId.matches("[A-Za-z0-9._:-]{1,128}")||text==null||text.length()>32768)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"prepared_material_limit");}
        @Override public String toString(){return "Material[redacted]";}
    }
    record Choice(String sourceId,String title){}
    List<Choice> choices(String username,String sessionId);
    List<Material> read(String username,String sessionId,List<String> selectedIds);
}
