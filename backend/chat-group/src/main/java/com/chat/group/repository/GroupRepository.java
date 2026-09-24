package com.chat.group.repository;

import com.chat.group.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupRepository extends JpaRepository<Group, Long> {
    List<Group> findByIdIn(List<Long> ids);
}
