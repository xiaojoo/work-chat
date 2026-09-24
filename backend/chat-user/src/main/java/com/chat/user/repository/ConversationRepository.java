package com.chat.user.repository;

import com.chat.user.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByTypeAndTargetId(Short type, Long targetId);

    Optional<Conversation> findByUserAIdAndUserBId(Long userAId, Long userBId);
}
