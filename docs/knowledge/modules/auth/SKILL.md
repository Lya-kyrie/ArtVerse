# Auth Module Skill

Sa-Token 认证模块：登录、注册、refresh cookie、渐进式人机验证、Redis 风控、CSRF 与角色鉴权。

## Architecture

```text
frontend (cookie session + adaptive challenge)
  ├─ GET  /api/auth/challenge/config
  ├─ POST /api/auth/login|register
  ├─ POST /api/auth/logout|refresh
  └─ POST /api/auth/kickout

AuthController
  ├─ AuthGuardService
  │   ├─ ClientIpResolver
  │   ├─ AuthRiskService
  │   └─ TurnstileHumanVerificationService
  ├─ AuthService
  │   └─ PasswordHashService (Argon2id + legacy BCrypt upgrade)
  ├─ RefreshTokenService
  └─ AuthCookieService
```

## Public vs Protected Routes

- Public:
  - `POST /api/auth/login`
  - `POST /api/auth/register`
  - `POST /api/auth/refresh`
  - `GET /api/auth/challenge/config`
  - `GET /api/square/**`
- Protected by Sa-Token login:
  - `POST /api/auth/logout`
  - `POST /api/auth/kickout`
  - `GET /api/auth/me`
  - `/api/user/**`
  - all remaining `/api/**`
- `/api/internal/guard/**` 不再公开，控制器内要求 `ADMIN`。

## Session Model

- Access token:
  - 由 Sa-Token 写入 `satoken` HttpOnly cookie。
  - 默认读 cookie，不再读 header。
  - `SameSite=Strict`，`Secure` 由 `ARTVERSE_AUTH_COOKIE_SECURE` 控制。
- Refresh token:
  - 独立 HttpOnly cookie，由 `AuthCookieService` 写入。
  - 生产安全 cookie 名会自动切到 `__Host-artverse-refresh`。
  - Redis 仅保存 SHA-256 hash，原始 token 不落库。
  - refresh token 一次性消费，重放会撤销该用户所有 refresh/access 会话。
- 迁移兼容:
  - `POST /api/auth/refresh` 先读 cookie。
  - 若开启 `artverse.auth.cookie.refresh-body-fallback-enabled`，仍接受旧请求体 `refresh_token` / `refreshToken`，用于 12 小时迁移窗口。

## Challenge / Human Verification

- 配置入口: `artverse.auth.challenge.*`
- 当前实现: Cloudflare Turnstile server-side validation
- 模式:
  - `DISABLED`: 不启用 challenge
  - `OBSERVE`: 前端可加载 challenge，但后端只记录失败/缺失，不阻断
  - `ENFORCE`: 缺失、失败、hostname/action 不匹配都会拒绝
- 后端校验:
  - token 长度上限 2048
  - 校验 `success`
  - 校验 `action=login|register`
  - 校验允许的 `hostname`
  - 网络异常或上游 5xx 归类为 `CHALLENGE_UNAVAILABLE`
- 对外错误码:
  - `CHALLENGE_REQUIRED`
  - `CHALLENGE_FAILED`
  - `CHALLENGE_UNAVAILABLE`

## Redis 风控

- 共用基础设施: `SlidingWindowRateLimiter`
- 登录 IP 桶:
  - `20 / 5min` 后要求 challenge
  - `100 / 5min` 后直接 `429 AUTH_RATE_LIMITED`
- 登录账号失败桶:
  - `3 / 15min` 后要求 challenge
  - 成功登录后清零
- 注册 IP 桶:
  - `10 / hour`，超限直接 `429`
- 降级登录:
  - 仅在 challenge 上游不可用时生效
  - 账号 `3 / 15min`
  - IP `10 / 5min`
- Redis key 不直接使用原始用户名/IP，而是经 `HmacSHA256` 派生。

## Client IP Semantics

- `ClientIpResolver` 仅在 `remoteAddr` 落在 `artverse.auth.proxy.trusted-cidrs` 时读取 `Forwarded` / `X-Forwarded-For`
- 从右向左选取首个非可信代理地址作为真实客户端 IP
- 未配置可信代理时直接使用 `remoteAddr`

## Password Policy

- 新注册密码:
  - 15 到 128 个字符
  - 允许 Unicode，不再强制字符类别组合
- 哈希:
  - 新密码使用 Argon2id
  - 旧 BCrypt 账号首次登录成功后自动升级为 Argon2id
  - 不存在用户名时也执行 dummy hash，减少时序枚举差异

## Data Normalization

- 用户名:
  - 注册与登录前统一 `trim`
  - 保持大小写敏感
- 邮箱:
  - 注册前统一 `trim + lowercase`
- Flyway `V46__auth_security_hardening.sql`:
  - 先检测 trim/lowercase 后的冲突
  - 再落盘修正并增加约束

## CSRF / Browser Contract

- 所有 `/api/**` 的 `POST/PUT/PATCH/DELETE` 请求需要:
  - `Origin` 命中 `artverse.cors-origins`
  - `X-ArtVerse-Client: web`
- 配置入口: `artverse.auth.csrf.mode`
  - `REPORT`: 只告警
  - `ENFORCE`: 返回 `403 CSRF_REJECTED`

## Frontend Contract

- 认证状态不再持久化到 `localStorage`
- `hydrateAuthSession()` 以 `/api/user/me` 为权威
- 首次 `401` 时尝试 `/api/auth/refresh`
- 登录:
  - 默认不展示 challenge
  - 后端返回 `CHALLENGE_REQUIRED` 后才显示 Turnstile
- 注册:
  - challenge 启用时直接要求完成验证

## Key Files

| File | Role |
|------|------|
| `ArtVerse/src/main/java/com/artverse/api/AuthController.java` | 登录/注册/refresh/challenge config 合同 |
| `ArtVerse/src/main/java/com/artverse/application/AuthService.java` | 用户归一化、注册、登录、密码升级 |
| `ArtVerse/src/main/java/com/artverse/application/RefreshTokenService.java` | refresh token issue/consume/revoke |
| `ArtVerse/src/main/java/com/artverse/security/AuthGuardService.java` | challenge + 风控编排 |
| `ArtVerse/src/main/java/com/artverse/security/AuthRiskService.java` | Redis 风控桶 |
| `ArtVerse/src/main/java/com/artverse/security/TurnstileHumanVerificationService.java` | Turnstile server-side 校验 |
| `ArtVerse/src/main/java/com/artverse/security/CsrfProtectionFilter.java` | CSRF Header + Origin 校验 |
| `ArtVerse/src/main/java/com/artverse/security/ClientIpResolver.java` | 可信代理 IP 解析 |
| `frontend/src/api.ts` | cookie 会话恢复、legacy refresh 迁移、CSRF headers |
| `frontend/src/components/LoginPage.tsx` | 登录/注册表单、Turnstile 挑战流 |

## Tests

- Backend:
  - `AuthControllerTest`
  - `AuthServiceTest`
  - `RefreshTokenServiceTest`
  - `RateLimitAspectTest`
  - `AuthDtosTest`
- Frontend:
  - `src/components/LoginPage.test.tsx`
  - `src/api.auth.test.ts`
