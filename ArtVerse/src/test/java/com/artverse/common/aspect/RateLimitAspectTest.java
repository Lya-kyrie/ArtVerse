package com.artverse.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.security.ClientIpResolver;
import com.artverse.security.SlidingWindowRateLimiter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RateLimitAspect")
class RateLimitAspectTest {

    private ArtVerseProperties properties;
    private SlidingWindowRateLimiter rateLimiter;
    private ClientIpResolver clientIpResolver;
    private RateLimitAspect aspect;
    private ProceedingJoinPoint joinPoint;
    private MethodSignature methodSignature;
    private MockedStatic<StpUtil> stpUtilMock;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        properties = new ArtVerseProperties();
        rateLimiter = mock(SlidingWindowRateLimiter.class);
        clientIpResolver = mock(ClientIpResolver.class);
        aspect = new RateLimitAspect(properties, rateLimiter, clientIpResolver);

        joinPoint = mock(ProceedingJoinPoint.class);
        methodSignature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);

        java.lang.reflect.Method method = TestController.class.getMethod("testEndpoint");
        when(methodSignature.getMethod()).thenReturn(method);

        stpUtilMock = org.mockito.Mockito.mockStatic(StpUtil.class);
        stpUtilMock.when(StpUtil::isLogin).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(clientIpResolver.resolve(request)).thenReturn("192.168.1.100");
    }

    @AfterEach
    void tearDown() {
        stpUtilMock.close();
        RequestContextHolder.resetRequestAttributes();
    }

    static class TestController {
        @RateLimit(windowSeconds = 60, maxRequests = 5, key = "test")
        public void testEndpoint() {
        }
    }

    @Test
    @DisplayName("allows request when under limit")
    void allowsWhenUnderLimit() throws Throwable {
        when(rateLimiter.incrementAndCheck(eq("rl:test:ip:192.168.1.100:default"), eq(60), eq(5)))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(3, true));
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.around(joinPoint);

        assertThat(result).isEqualTo("OK");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("blocks request when over limit")
    void blocksWhenOverLimit() {
        when(rateLimiter.incrementAndCheck(eq("rl:test:ip:192.168.1.100:default"), eq(60), eq(5)))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(6, false));

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 429);
    }

    @Test
    @DisplayName("bypasses when rate limiting is disabled")
    void bypassWhenDisabled() throws Throwable {
        properties.getRateLimit().setEnabled(false);
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.around(joinPoint);

        assertThat(result).isEqualTo("OK");
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("uses userId key when logged in")
    void usesUserIdWhenLoggedIn() throws Throwable {
        stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(42L);
        when(rateLimiter.incrementAndCheck(argThat(key -> key.equals("rl:test:u42:default")), eq(60), eq(5)))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(1, true));
        when(joinPoint.proceed()).thenReturn("OK");

        aspect.around(joinPoint);

        verify(rateLimiter).incrementAndCheck("rl:test:u42:default", 60, 5);
    }
}
