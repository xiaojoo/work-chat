package com.chat.user.dto;

public class FriendDTO {
    private Long id;
    private Long userId;
    private Long friendId;
    private String username;
    private String nickname;
    private String avatar;
    private Short status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getFriendId() { return friendId; }
    public void setFriendId(Long friendId) { this.friendId = friendId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Short getStatus() { return status; }
    public void setStatus(Short status) { this.status = status; }
}
