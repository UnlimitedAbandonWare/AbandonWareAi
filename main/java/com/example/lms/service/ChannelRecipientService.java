// src/main/java/com/example/lms/service/ChannelRecipientService.java
package com.example.lms.service;

import java.util.List;



/**
 * Channel 移쒓뎄-紐⑸줉 議고쉶 ?쒕퉬??怨꾩빟
 */
public interface ChannelRecipientService {

    /**
     * accessToken + offset + limit ?쇰줈 吏곸젒 ?몄텧.
     * <p>
     * ?섏씠吏 ?ш린(limit)???쒕퉬???대? ?섎뱶肄붾뵫???꾨땲???몄텧遺(Controller/Config)?먯꽌 ?뺤콉?쇰줈 二쇱엯?쒕떎.
     */
    List<String> fetchRecipientIds(String accessToken, int offset, int limit);
}
