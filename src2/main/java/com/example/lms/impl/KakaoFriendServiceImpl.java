// src/main/java/com/example/lms/service/impl/KakaoFriendServiceImpl.java
package com.example.lms.impl;

import com.example.lms.dto.KakaoFriends;
import com.example.lms.service.KakaoFriendService;
import com.example.lms.service.KakaoOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KakaoFriendServiceImpl implements KakaoFriendService {

    private final KakaoOAuthService oauthService;

    @Override
    public List<String> fetchFriendUuids(String accessToken, int offset) {
        KakaoFriends res = oauthService.friends(accessToken, offset);
        return res.getElements()
                .stream()
                .map(KakaoFriends.Element::getUuid)
                .toList();
    }
}
