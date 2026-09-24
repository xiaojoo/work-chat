package com.chat.user.repository;

import com.chat.user.model.Friend;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FriendRepository extends JpaRepository<Friend, Long> {
    List<Friend> findByUserIdAndStatus(Long userId, Short status);
    List<Friend> findByFriendIdAndStatus(Long friendId, Short status);
    boolean existsByUserIdAndFriendId(Long userId, Long friendId);
}
