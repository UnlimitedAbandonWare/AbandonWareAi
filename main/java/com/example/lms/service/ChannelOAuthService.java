package com.example.lms.service;

import com.example.lms.dto.ChannelRecipients;
import java.util.List;



/** Channel OAuth + 硫붿떆吏 ?꾩넚 怨듯넻 API */
public interface ChannelOAuthService {

    /* ????????? 湲곗〈 ????????? */
    /** ?멸? 肄붾뱶瑜??≪꽭???좏겙?쇰줈 援먰솚 */
    String exchangeCodeForToken(String code);

    void sendMemoDefault(String accessToken, String text, String linkUrl);

    ChannelRecipients recipients(String accessToken, int offset, int limit);

    List<String> fetchRecipientIds(String accessToken, int offset, int limit);
}
