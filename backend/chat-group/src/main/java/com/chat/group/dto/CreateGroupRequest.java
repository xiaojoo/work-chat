package com.chat.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CreateGroupRequest {

    @NotBlank(message = "群名不能为空")
    @Size(max = 100, message = "群名最长100字")
    private String name;

    private String avatar;

    private Long ownerId;

    /** 群简介：稿子里的「群聊简介（选填）」，落到 chat_group.announcement */
    @Size(max = 1000, message = "群简介最长1000字")
    private String announcement;

    public String getAnnouncement() { return announcement; }
    public void setAnnouncement(String announcement) { this.announcement = announcement; }

    private List<Long> memberIds;

    /** 1 普通群 / 2 项目群 / 3 部门群，不传按普通群 */
    private Short groupType;

    public Short getGroupType() { return groupType; }
    public void setGroupType(Short groupType) { this.groupType = groupType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public List<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(List<Long> memberIds) { this.memberIds = memberIds; }
}
