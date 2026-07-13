package com.artverse.api;

import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.RefreshRequest;
import com.artverse.application.AuthService;
import com.artverse.application.RefreshTokenService;
import com.artverse.application.RefreshTokenService.Consumption;
import com.artverse.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @InjectMocks
    private AuthController controller;

    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        stpUtil = mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    @Nested
    @DisplayName("kickout")
    class Kickout {

        @Test
        @DisplayName("blocks unauthenticated user with 401")
        void unauthenticated() {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertThatThrownBy(() -> controller.kickout(5L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(401);
                    });
        }

        @Test
        @DisplayName("blocks non-admin user with 403")
        void nonAdmin() {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(() -> StpUtil.checkRole("ADMIN"))
                    .thenThrow(new NotRoleException("ADMIN"));

            assertThatThrownBy(() -> controller.kickout(5L))
                    .isInstanceOf(NotRoleException.class);
        }

        @Test
        @DisplayName("allows admin to kick out user")
        void adminCanKickout() {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(() -> StpUtil.checkRole("ADMIN")).then(inv -> null);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

            controller.kickout(5L);

            stpUtil.verify(() -> StpUtil.kickout(5L));
            verify(refreshTokenService).revokeAll(5L);
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("blocks unauthenticated with 401")
        void unauthenticated() {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertThatThrownBy(() -> controller.refresh(null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(401);
                    });
        }

        @Test
        @DisplayName("renews timeout and returns new token pair")
        void renewsTimeout() {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            SaTokenInfo info = new SaTokenInfo();
            info.setTokenName("satoken");
            info.setTokenValue("access-token");
            info.setTokenTimeout(43200);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(info);
            when(refreshTokenService.issue(1L)).thenReturn("new-refresh-token");
            when(refreshTokenService.getTimeoutSeconds()).thenReturn(604800L);

            var result = controller.refresh(null);

            stpUtil.verify(() -> StpUtil.renewTimeout(43200));
            assertThat(result.tokenValue()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("rotates refresh token and access token when provided")
        void rotatesRefreshToken() {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            when(refreshTokenService.consume("old-rt", 1L)).thenReturn(Consumption.valid(1L));
            SaTokenInfo info = new SaTokenInfo();
            info.setTokenName("satoken");
            info.setTokenValue("new-access");
            info.setTokenTimeout(43200);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(info);
            when(refreshTokenService.issue(1L)).thenReturn("new-rt");
            when(refreshTokenService.getTimeoutSeconds()).thenReturn(604800L);

            var result = controller.refresh(new RefreshRequest("old-rt"));

            verify(refreshTokenService).consume("old-rt", 1L);
            stpUtil.verify(StpUtil::logout);
            stpUtil.verify(() -> StpUtil.login(1L, "PC"));
            assertThat(result.refreshToken()).isEqualTo("new-rt");
        }

        @Test
        @DisplayName("restores access session from refresh token when cookie is missing")
        void restoresMissingAccessSession() {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);
            when(refreshTokenService.consume("valid-rt", null)).thenReturn(Consumption.valid(1L));
            SaTokenInfo info = new SaTokenInfo();
            info.setTokenName("satoken");
            info.setTokenValue("restored-access");
            info.setTokenTimeout(43200);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(info);
            when(refreshTokenService.issue(1L)).thenReturn("rotated-rt");

            var result = controller.refresh(new RefreshRequest("valid-rt"));

            stpUtil.verify(() -> StpUtil.login(1L, "PC"));
            stpUtil.verify(StpUtil::logout, never());
            assertThat(result.tokenValue()).isEqualTo("restored-access");
            assertThat(result.refreshToken()).isEqualTo("rotated-rt");
        }

        @Test
        @DisplayName("revokes all sessions on refresh token reuse")
        void detectsReuse() {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);
            when(refreshTokenService.consume("stolen-rt", null)).thenReturn(Consumption.reused(1L));

            assertThatThrownBy(() -> controller.refresh(new RefreshRequest("stolen-rt")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(401);
                    });

            verify(refreshTokenService).revokeAll(1L);
            stpUtil.verify(() -> StpUtil.kickout(1L));
        }

        @Test
        @DisplayName("rejects an unknown refresh token without revoking another session")
        void rejectsUnknownTokenWithoutRevocation() {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);
            when(refreshTokenService.consume("unknown-rt", null)).thenReturn(Consumption.invalid());

            assertThatThrownBy(() -> controller.refresh(new RefreshRequest("unknown-rt")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(401));

            verify(refreshTokenService, never()).revokeAll(anyLong());
            stpUtil.verify(() -> StpUtil.kickout(any()), never());
        }
    }

    @Nested
    @DisplayName("me")
    class Me {

        @Test
        @DisplayName("blocks unauthenticated with 401")
        void unauthenticated() {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertThatThrownBy(() -> controller.me())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(401);
                    });
        }

        @Test
        @DisplayName("returns token info for authenticated user")
        void authenticated() {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            SaTokenInfo info = new SaTokenInfo();
            info.setTokenName("satoken");
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(info);

            Object result = controller.me();

            assertThat(result).isSameAs(info);
        }
    }
}
