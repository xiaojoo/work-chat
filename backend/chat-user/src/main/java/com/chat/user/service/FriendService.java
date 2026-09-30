package com.chat.user.service;

import com.chat.user.dto.FriendDTO;
import com.chat.user.model.Friend;
import com.chat.user.model.User;
import com.chat.user.repository.FriendRepository;
import com.chat.user.repository.UserRepository;
import com.chat.user.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FriendService {

    private final FriendRepository friendRepository;
    private final UserRepository userRepository;
    private final IdGenerator ids;

    public FriendService(FriendRepository friendRepository,
                         UserRepository userRepository,
                         IdGenerator ids) {
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
        this.ids = ids;
    }

    @Transactional
    public void addFriend(Long userId, Long friendId) {
        if (userId.equals(friendId)) {
            throw new RuntimeException("不能添加自己为好友");
        }

        if (!userRepository.existsById(friendId)) {
            throw new RuntimeException("用户不存在");
        }

        if (friendRepository.existsByUserIdAndFriendIdAndStatus(userId, friendId, (short) 1)) {
            throw new RuntimeException("已经是好友");
        }
        linkBothWays(userId, friendId);
    }

    /**
     * 两个方向各落一条（设计文档第六节：A→B、B→A 分别保存，查列表才不用两边拼）。
     * <p>
     * 这一对之间可能已经有一条 status=0 的软删记录——删除好友是软删，不是删行。
     * 那种情况把那条复活，别再插一条：表上有 uk_friend(user_id, friend_id) 唯一键，
     * 插第二条会直接撞（实测：删掉第二个测试号之后再加，旧代码回"已经是好友"，人就永远加不回来了）。
     */
    private void linkBothWays(Long a, Long b) {
        link(a, b);
        link(b, a);
    }

    private void link(Long owner, Long other) {
        Friend found = friendRepository.findByUserIdAndFriendId(owner, other).orElse(null);
        if (found != null) {
            found.setStatus((short) 1);
            friendRepository.save(found);
            return;
        }
        Friend f = new Friend();
        f.setId(ids.nextId());
        f.setUserId(owner);
        f.setFriendId(other);
        f.setStatus((short) 1);
        friendRepository.save(f);
    }

    @Transactional
    public void removeFriend(Long userId, Long friendId) {
        List<Friend> friends = friendRepository.findByUserIdAndStatus(userId, (short) 1);
        friends.stream()
                .filter(f -> f.getFriendId().equals(friendId))
                .findFirst()
                .ifPresent(f -> {
                    f.setStatus((short) 0);
                    friendRepository.save(f);
                });

        List<Friend> reverse = friendRepository.findByUserIdAndStatus(friendId, (short) 1);
        reverse.stream()
                .filter(f -> f.getFriendId().equals(userId))
                .findFirst()
                .ifPresent(f -> {
                    f.setStatus((short) 0);
                    friendRepository.save(f);
                });
    }

    public List<FriendDTO> getFriendList(Long userId) {
        List<Friend> friends = friendRepository.findByUserIdAndStatus(userId, (short) 1);
        List<FriendDTO> result = new ArrayList<>();

        for (Friend friend : friends) {
            userRepository.findById(friend.getFriendId()).ifPresent(user -> {
                FriendDTO dto = new FriendDTO();
                dto.setId(friend.getId());
                dto.setUserId(userId);
                dto.setFriendId(user.getId());
                dto.setUsername(user.getUsername());
                dto.setNickname(user.getNickname());
                dto.setAvatar(user.getAvatar());
                dto.setStatus(friend.getStatus());
                result.add(dto);
            });
        }

        return result;
    }

    /** 只看成立的关系：软删过的那条不算。好友信息页的「删除好友」一行就是靠这个决定出不出，
     *  带 status=0 也返回 true 的话，删完之后那行还挂在页上骗人 */
    public boolean areFriends(Long userId, Long friendId) {
        return friendRepository.existsByUserIdAndFriendIdAndStatus(userId, friendId, (short) 1);
    }
}
