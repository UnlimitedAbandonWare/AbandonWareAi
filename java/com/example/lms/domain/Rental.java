package com.example.lms.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 도서 대여 정보를 나타내는 JPA 엔티티.
 * 명시적인 테이블/컬럼명과 시퀀스 기반 ID 생성을 사용합니다.
 */
@Entity
@Table(name = "RENTALS")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rental {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "rentals_seq_gen"
    )
    @SequenceGenerator(
            name = "rentals_seq_gen",
            sequenceName = "RENTALS_SEQ",
            allocationSize = 1
    )
    private Long id;

    @Column(name = "book_title", nullable = false)
    private String bookTitle;

    @Column(name = "renter_name", nullable = false)
    private String renterName;

    @Column(name = "rented_at", nullable = false)
    private LocalDateTime rentedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * 엔티티가 영속화되기 전에 대여 시각을 현재 시간으로 자동 설정합니다.
     */
    @PrePersist
    protected void onCreate() {
        if (rentedAt == null) {
            rentedAt = LocalDateTime.now();
        }
    }
}
