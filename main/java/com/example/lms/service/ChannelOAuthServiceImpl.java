package com.example.lms.service;

import com.example.lms.dto.ChannelRecipients;
import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Disabled provider adapter kept as a Spring bean so channel controllers fail soft
 * without calling an unconfigured OAuth provider.
 */
@Service("channelOAuthServiceDisabled")
public class ChannelOAuthServiceImpl implements ChannelOAuthService {
    private static final Logger log = LoggerFactory.getLogger(ChannelOAuthServiceImpl.class);
    private static final String DISABLED_REASON = "channel oauth provider is not configured";
    private static final String DISABLED_REASON_CODE = "channel_oauth_provider_not_configured";

    @Override
    public String exchangeCodeForToken(String code) {
        traceProviderDisabled("exchange_code");
        log.info("[channel.oauth] accepted=false disabledReason={}", DISABLED_REASON);
        return null;
    }

    @Override
    public void sendMemoDefault(String accessToken, String text, String linkUrl) {
        traceProviderDisabled("send_memo");
        log.info("[channel.oauth] memo skipped disabledReason={}", DISABLED_REASON);
    }

    @Override
    public ChannelRecipients recipients(String accessToken, int offset, int limit) {
        traceProviderDisabled("recipients");
        ChannelRecipients out = new ChannelRecipients();
        out.setElements(List.of());
        return out;
    }

    @Override
    public List<String> fetchRecipientIds(String accessToken, int offset, int limit) {
        traceProviderDisabled("fetch_recipient_ids");
        return List.of();
    }

    private static void traceProviderDisabled(String operation) {
        TraceStore.put("channel.oauth.providerDisabled", true);
        TraceStore.put("channel.oauth.disabledReason", DISABLED_REASON_CODE);
        TraceStore.put("channel.oauth.skipped.reason", DISABLED_REASON_CODE);
        TraceStore.put("channel.oauth.operation", operation);
    }
}
