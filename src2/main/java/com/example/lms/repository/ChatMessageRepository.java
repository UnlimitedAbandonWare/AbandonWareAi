// =======================================================================
// 2. ChatMessageRepository.java
// 위치: com.example.lms.repository.ChatMessageRepository
// =======================================================================
package com.example.lms.repository;

import com.example.lms.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
}
