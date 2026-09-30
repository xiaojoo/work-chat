package com.chat.user.repository;

import com.chat.user.model.ControlEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ControlEventRepository extends JpaRepository<ControlEvent, Long> {
    List<ControlEvent> findBySessionIdOrderByCreateTimeAsc(String sessionId);
}
