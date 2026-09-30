package com.chat.user.repository;

import com.chat.user.model.ControlSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ControlSessionRepository extends JpaRepository<ControlSession, Long> {

    Optional<ControlSession> findBySessionId(String sessionId);

    @Query("select s from ControlSession s where s.initiatorId = :uid or s.targetId = :uid order by s.requestTime desc")
    List<ControlSession> findByUser(@Param("uid") Long uid);
}
