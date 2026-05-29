package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public TokenService.TokenPair register(String username, String email, String password) {
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
        userRepository.save(user);

        return tokenService.generateTokens(user.getId(), user.getUsername());
    }

    @Transactional(readOnly = true)
    public TokenService.TokenPair login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return tokenService.generateTokens(user.getId(), user.getUsername());
    }
}
