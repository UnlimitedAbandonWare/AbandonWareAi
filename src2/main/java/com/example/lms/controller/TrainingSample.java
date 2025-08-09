package com.example.lms.controller;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class TrainingSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // AI에게 제시할 문장 또는 질문 (User 역할)
    @Column(nullable = false, length = 1000)
    private String prompt;

    // AI가 답변해야 하는 모범 답안 (Assistant 역할)
    @Column(nullable = false, length = 1000)
    private String completion;

    public TrainingSample(String prompt, String completion) {
        this.prompt = prompt;
        this.completion = completion;
    }
}