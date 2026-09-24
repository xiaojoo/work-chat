package com.chat.group.dto;

public class GroupDTO {
    private Long id;
    private String name;
    private String avatar;
    private Long ownerId;
    private String announcement;
    private Integer memberCount;
    private Short groupType;

    public Short getGroupType() { return groupType; }
    public void setGroupType(Short groupType) { this.groupType = groupType; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getAnnouncement() { return announcement; }
    public void setAnnouncement(String announcement) { this.announcement = announcement; }
    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }
}
