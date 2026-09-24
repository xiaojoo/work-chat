package com.chat.user.repository;

import com.chat.user.model.UserConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserConversationRepository extends JpaRepository<UserConversation, Long> {
    List<UserConversation> findByUserIdOrderByUpdateTimeDesc(Long userId);
    Optional<UserConversation> findByUserIdAndConversationId(Long userId, Long conversationId);
    List<UserConversation> findByConversationId(Long conversationId);
}
