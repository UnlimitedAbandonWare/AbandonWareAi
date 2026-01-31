
package com.rc111.merge21.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/probe/search")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="probe.search.enabled", havingValue="true")
public class SearchProbeController {
    @Value("${probe.search.enabled:true}")
    boolean enabled;
    @Value("${probe.search.admin-token:}")
    String admin;

    @PostMapping
    public String probe(@RequestHeader(value="X-Admin-Token", required=false) String hdr) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "probe disabled");
        if (admin!=null && !admin.isEmpty() && (hdr==null || !hdr.equals(admin)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad admin token");
        return "{\"ok\":true}";
    }
}
