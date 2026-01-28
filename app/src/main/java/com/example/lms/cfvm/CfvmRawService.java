
package com.example.lms.cfvm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class CfvmRawService {
    private static final Logger log = LoggerFactory.getLogger(CfvmRawService.class);
    public void logAutoLearnFailure(String query, String reason, Map<String,Object> ctx){
        log.warn("[AUTOLEARN_FAIL] query='{}' reason='{}' ctx={}", query, reason, ctx);
    }
}
