import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAuth, getChallengeConfig, getUser, hydrateAuthSession } from './api';

describe('api auth session hydration', () => {
  beforeEach(() => {
    localStorage.clear();
    clearAuth();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    clearAuth();
  });

  it('restores a cookie session via legacy refresh token fallback and clears persisted auth data', async () => {
    localStorage.setItem('artverse.refreshToken', 'legacy-refresh-token');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ detail: 'Login expired', code: 'AUTH_EXPIRED' }), { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ authenticated: true, token_timeout: 3600, refresh_token_timeout: 43200 }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 7, username: 'alice', email: 'alice@example.com' }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const authenticated = await hydrateAuthSession();

    expect(authenticated).toBe(true);
    expect(getUser()).toEqual({ id: 7, username: 'alice', email: 'alice@example.com' });
    expect(localStorage.getItem('artverse.refreshToken')).toBeNull();
    expect(localStorage.getItem('artverse.user')).toBeNull();
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/refresh', expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      body: JSON.stringify({ refresh_token: 'legacy-refresh-token' }),
    }));
  });

  it('maps snake_case challenge config fields for the login page', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({
      enabled: true,
      provider: 'turnstile',
      site_key: '1x00000000000000000000AA',
      registration_required: true,
      login_mode: 'adaptive',
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const config = await getChallengeConfig();

    expect(config).toEqual({
      enabled: true,
      provider: 'turnstile',
      siteKey: '1x00000000000000000000AA',
      registrationRequired: true,
      loginMode: 'adaptive',
    });
  });
});
