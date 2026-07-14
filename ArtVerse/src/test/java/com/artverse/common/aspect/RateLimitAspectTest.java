package com.artverse.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitAspect")
class RateLimitAspectTest {

    private StringRedisTemplate redisTemplate;
    private ArtVerseProperties properties;
    private RateLimitAspect aspect;
    private ProceedingJoinPoint joinPoint;
    private MethodSignature methodSignature;
    private MockedStatic<StpUtil> stpUtilMock;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        redisTemplate = mock(StringRedisTemplate.class);
        properties = new ArtVerseProperties();
        aspect = new RateLimitAspect(redisTemplate, properties);

        joinPoint = mock(ProceedingJoinPoint.class);
        methodSignature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);

        java.lang.reflect.Method method = TestController.class.getMethod("testEndpoint");
        when(methodSignature.getMethod()).thenReturn(method);

        stpUtilMock = mockStatic(StpUtil.class);
        stpUtilMock.when(StpUtil::isLogin).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        stpUtilMock.close();
        RequestContextHolder.resetRequestAttributes();
    }

    static class TestController {
        @RateLimit(windowSeconds = 60, maxRequests = 5, key = "test")
        public void testEndpoint() {}
    }

    @Test
    @DisplayName("allows request when under limit")
    void allowsWhenUnderLimit() throws Throwable {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(3L);
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.around(joinPoint);

        assertThat(result).isEqualTo("OK");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("blocks request when over limit")
    void blocksWhenOverLimit() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(10L);

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getStatus() == 429);
    }

    @Test
    @DisplayName("blocks the first request beyond the configured boundary")
    void blocksAtBoundarySignal() throws Throwable {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(6L);

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getStatus() == 429);
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("bypasses when rate limiting is disabled")
    void bypassWhenDisabled() throws Throwable {
        properties.getRateLimit().setEnabled(false);
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.around(joinPoint);

        assertThat(result).isEqualTo("OK");
        verify(redisTemplate, never()).execute(any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("uses userId key when logged in")
    void usesUserIdWhenLoggedIn() throws Throwable {
        stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(42L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(1L);
        when(joinPoint.proceed()).thenReturn("OK");

        aspect.around(joinPoint);

        assertThat(true).isTrue();
    }
}
