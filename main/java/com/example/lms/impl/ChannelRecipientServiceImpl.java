// src/main/java/com/example/lms/service/impl/ChannelRecipientServiceImpl.java
package com.example.lms.impl;

import com.example.lms.dto.ChannelRecipients;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChannelRecipientService;
import com.example.lms.service.ChannelOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;




@Service
@RequiredArgsConstructor
public class ChannelRecipientServiceImpl implements ChannelRecipientService {

    private final ChannelOAuthService oauthService;

    @Override
    public List<String> fetchRecipientIds(String accessToken, int offset, int limit) {
        ChannelRecipients res = oauthService.recipients(accessToken, offset, limit);
        if (res == null) {
            traceNoRecipients("provider_empty_response");
            return List.of();
        }
        if (res.getElements() == null) {
            traceNoRecipients("provider_empty_elements");
            return List.of();
        }
        List<String> ids = res.getElements()
                .stream()
                .map(ChannelRecipients.Element::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        TraceStore.put("channel.recipients.rawCount", res.getElements().size());
        TraceStore.put("channel.recipients.returnedCount", ids.size());
        return ids;
    }

    private static void traceNoRecipients(String reason) {
        TraceStore.put("channel.recipients.rawCount", 0);
        TraceStore.put("channel.recipients.returnedCount", 0);
        TraceStore.put("channel.recipients.skipped.reason", reason);
    }
}
