package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.BCryptPasswordEncoder;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 鉴权业务（Sa-Token 方案）。
 * <p>
 * 不再依赖 TokenService；token 生成由 Sa-Token StpUtil 在 Controller 负责。
 *
 * @see <a href="docs/knowledge/modules/auth/SKILL.md">auth 模块 Skill</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public User register(String username, String email, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(409, "用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(409, "邮箱已被注册");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return user;
    }
}
