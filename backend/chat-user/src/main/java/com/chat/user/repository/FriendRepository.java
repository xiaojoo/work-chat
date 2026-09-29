package com.chat.user.repository;

import com.chat.user.model.Friend;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FriendRepository extends JpaRepository<Friend, Long> {
    List<Friend> findByUserIdAndStatus(Long userId, Short status);
    List<Friend> findByFriendIdAndStatus(Long friendId, Short status);

    /** 只看还成立的那一条关系。删除是软删（status 置 0），
     *  不带 status 的判重会把"已经删掉的好友"说成"已经是好友"，那个人就再也加不回来了 */
    boolean existsByUserIdAndFriendIdAndStatus(Long userId, Long friendId, Short status);

    /** 这一对之间的记录，不分有效/软删——重新添加时要复活软删那条，
     *  因为表上有 uk_friend(user_id, friend_id) 唯一键，插第二条会撞 */
    Optional<Friend> findByUserIdAndFriendId(Long userId, Long friendId);
}
