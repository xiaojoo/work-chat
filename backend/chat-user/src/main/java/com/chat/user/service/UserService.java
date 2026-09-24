package com.chat.user.service;

import com.chat.user.dto.LoginRequest;
import com.chat.user.dto.RegisterRequest;
import com.chat.user.dto.TokenResponse;
import com.chat.user.model.User;
import com.chat.user.repository.UserRepository;
import com.chat.user.util.IdGenerator;
import com.chat.user.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final IdGenerator ids;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       IdGenerator ids) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.ids = ids;
    }

    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setId(ids.nextId());
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());

        userRepository.save(user);

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        return new TokenResponse(accessToken, refreshToken, user.getId(), user.getUsername());
    }

    public TokenResponse login(LoginRequest request) {
        User user = resolveAccount(request.getUsername())
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        return new TokenResponse(accessToken, refreshToken, user.getId(), user.getUsername());
    }

    /**
     * 登录名可以是用户名、邮箱或手机号。同一个邮箱/手机号被多人占用时，
     * 宁可报「用户名或密码错误」，也不在身份不明确的情况下放行。
     */
    private Optional<User> resolveAccount(String account) {
        String key = account == null ? "" : account.trim();
        if (key.isEmpty()) return Optional.empty();

        Optional<User> byName = userRepository.findByUsername(key);
        if (byName.isPresent()) return byName;

        List<User> byEmail = userRepository.findByEmailIgnoreCase(key);
        if (byEmail.size() == 1) return Optional.of(byEmail.get(0));

        List<User> byPhone = userRepository.findByPhone(key);
        if (byPhone.size() == 1) return Optional.of(byPhone.get(0));

        return Optional.empty();
    }

    public TokenResponse refreshToken(String refreshToken) {
        if (!jwtUtil.isValid(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new RuntimeException("Refresh Token 无效");
        }

        Long userId = jwtUtil.getUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());

        return new TokenResponse(newAccessToken, newRefreshToken, user.getId(), user.getUsername());
    }
}
