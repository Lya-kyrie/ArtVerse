package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public User requireCurrentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
    }
}
