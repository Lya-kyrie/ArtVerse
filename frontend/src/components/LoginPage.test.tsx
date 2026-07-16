import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';
import { ApiError, getChallengeConfig, loginUser, registerUser } from '../api';

vi.mock('../api', () => ({
  ApiError: class ApiError extends Error {
    code?: string;

    constructor(message: string, options?: { code?: string }) {
      super(message);
      this.code = options?.code;
    }
  },
  getChallengeConfig: vi.fn(),
  loginUser: vi.fn(),
  registerUser: vi.fn(),
}));

vi.mock('./TurnstileWidget', () => ({
  default: ({
    onTokenChange,
    onStatusChange,
  }: {
    onTokenChange: (token: string | null) => void;
    onStatusChange: (status: string) => void;
  }) => (
    <button
      type="button"
      onClick={() => {
        onTokenChange('challenge-token');
        onStatusChange('verified');
      }}
    >
      Solve challenge
    </button>
  ),
}));

describe('LoginPage security flow', () => {
  const openRegister = () => {
    fireEvent.click(screen.getAllByRole('button', { name: '注册' })[0]);
  };

  const submitRegister = () => {
    fireEvent.click(screen.getAllByRole('button', { name: '注册' })[1]);
  };

  const submitLogin = () => {
    fireEvent.click(screen.getAllByRole('button', { name: '登录' })[1]);
  };

  beforeEach(() => {
    vi.mocked(getChallengeConfig).mockResolvedValue({
      enabled: true,
      provider: 'turnstile',
      siteKey: 'site-key',
      registrationRequired: true,
      loginMode: 'adaptive',
    });
    vi.mocked(loginUser).mockReset();
    vi.mocked(registerUser).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('does not show a login-only message in register mode', async () => {
    render(<LoginPage onAuthSuccess={vi.fn()} message="请先登录后再使用该功能" />);

    expect(screen.getByText('请先登录后再使用该功能')).toBeTruthy();
    openRegister();

    await waitFor(() => expect(screen.queryByText('请先登录后再使用该功能')).toBeNull());
  });

  it('blocks register submit until the challenge token exists', async () => {
    render(<LoginPage onAuthSuccess={vi.fn()} />);

    openRegister();
    await screen.findByRole('button', { name: 'Solve challenge' });
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'alice@example.com' } });
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'averylongpassword' } });
    fireEvent.change(screen.getByLabelText('确认密码'), { target: { value: 'averylongpassword' } });
    submitRegister();

    expect((await screen.findByRole('alert')).textContent).toContain('请先完成人机验证后再注册');
    expect(registerUser).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Solve challenge' }));
    submitRegister();

    await waitFor(() => expect(registerUser).toHaveBeenCalledWith(
      'alice',
      'alice@example.com',
      'averylongpassword',
      'challenge-token',
    ));
  });

  it('shows a password length error instead of crashing on short register passwords', async () => {
    render(<LoginPage onAuthSuccess={vi.fn()} />);

    openRegister();
    await screen.findByRole('button', { name: 'Solve challenge' });
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'alice@example.com' } });
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'short-password' } });
    fireEvent.change(screen.getByLabelText('确认密码'), { target: { value: 'short-password' } });
    submitRegister();

    expect((await screen.findByRole('alert')).textContent).toContain('注册密码建议使用短语式长密码，长度至少 15 个字符。');
    expect(registerUser).not.toHaveBeenCalled();
  });

  it('prompts for a login challenge only after the backend requests it', async () => {
    const onAuthSuccess = vi.fn();
    vi.mocked(loginUser)
      .mockRejectedValueOnce(new ApiError('请先完成人机验证', { code: 'CHALLENGE_REQUIRED' }))
      .mockResolvedValueOnce(undefined);

    render(<LoginPage onAuthSuccess={onAuthSuccess} />);

    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'averylongpassword' } });
    submitLogin();

    expect((await screen.findByRole('alert')).textContent).toContain('请先完成人机验证');
    expect(await screen.findByRole('button', { name: 'Solve challenge' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Solve challenge' }));
    submitLogin();

    await waitFor(() => expect(loginUser).toHaveBeenLastCalledWith(
      'alice',
      'averylongpassword',
      'challenge-token',
    ));
    await waitFor(() => expect(onAuthSuccess).toHaveBeenCalled());
  });
});
