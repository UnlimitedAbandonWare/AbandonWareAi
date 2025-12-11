package com.example.lms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * UserPreferenceProfile
 *
 * ownerKey 별로 최근 사용 설정을 JSON 형태로 저장하는 엔티티입니다.
 * Jammini Memory / Projection 모드에서 세션 외부의 선호도 캐시로 사용될 수 있습니다.
 */
@Entity
@Table(name = "user_preference_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_key", unique = true, nullable = false, length = 128)
    private String ownerKey;

    // 최근 사용 설정 전체를 JSON으로 저장
    @Column(name = "profile_meta", columnDefinition = "TEXT")
    private String profileMeta;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
