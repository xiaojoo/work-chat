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

        if (friendRepository.existsByUserIdAndFriendId(userId, friendId)) {
            throw new RuntimeException("已经是好友");
        }

        Friend friend1 = new Friend();
        friend1.setId(ids.nextId());
        friend1.setUserId(userId);
        friend1.setFriendId(friendId);
        friend1.setStatus((short) 1);
        friendRepository.save(friend1);

        Friend friend2 = new Friend();
        friend2.setId(ids.nextId());
        friend2.setUserId(friendId);
        friend2.setFriendId(userId);
        friend2.setStatus((short) 1);
        friendRepository.save(friend2);
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

    public boolean areFriends(Long userId, Long friendId) {
        return friendRepository.existsByUserIdAndFriendId(userId, friendId);
    }
}
