package com.chat.user.repository;

import com.chat.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /**
     * 按用户名或昵称模糊搜索（不区分大小写）
     */
    List<User> findByUsernameContainingIgnoreCaseOrNicknameContainingIgnoreCase(String username, String nickname);

    /** 邮箱和手机号都没有唯一约束，所以返回 List：命中多条说明身份不明确，登录要拒绝 */
    List<User> findByEmailIgnoreCase(String email);
    List<User> findByPhone(String phone);
}
