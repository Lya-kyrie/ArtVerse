import { useState } from 'react';
import { KeyRound, LogIn, Mail, Sparkles, User, UserPlus } from 'lucide-react';
import { loginUser, registerUser } from '../api';

interface Props {
  onAuthSuccess: () => void;
  variant?: 'page' | 'modal';
  message?: string;
  onCancel?: () => void;
}

export default function LoginPage({ onAuthSuccess, variant = 'page', message, onCancel }: Props) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const isModal = variant === 'modal';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!username.trim() || !password.trim()) {
      setError('请填写用户名和密码');
      return;
    }
    if (mode === 'register' && !email.trim()) {
      setError('请填写邮箱');
      return;
    }
    setLoading(true);
    try {
      if (mode === 'login') {
        await loginUser(username.trim(), password);
      } else {
        await registerUser(username.trim(), email.trim(), password);
      }
      onAuthSuccess();
    } catch (err: any) {
      setError(err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={(isModal ? '' : 'min-h-screen bg-bg-base ') + 'flex items-center justify-center p-4'}>
      <div className="w-full max-w-sm">
        <div className="mb-4 flex items-center justify-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-accent text-white shadow-sm">
            <Sparkles size={20} />
          </div>
          <div className="text-left">
            <h1 className="font-display text-lg font-bold text-text-primary">ArtVerse</h1>
            <p className="text-xs text-text-muted">AI 漫画创作工坊</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 rounded-lg border border-border bg-bg-raised p-6 shadow-[0_18px_60px_rgba(24,27,25,0.16)]">
          {message && (
            <p className="rounded-md border border-accent-secondary/20 bg-accent-secondary/10 px-3 py-2 text-sm text-accent-secondary">
              {message}
            </p>
          )}

          <div className="grid grid-cols-2 gap-1 rounded-md bg-bg-surface p-1" aria-label="账号操作">
            <button
              type="button"
              onClick={() => { setMode('login'); setError(''); }}
              className={'rounded px-3 py-2 text-xs font-medium transition-colors ' + (mode === 'login' ? 'bg-bg-raised text-text-primary shadow-sm' : 'text-text-muted hover:text-text-primary')}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => { setMode('register'); setError(''); }}
              className={'rounded px-3 py-2 text-xs font-medium transition-colors ' + (mode === 'register' ? 'bg-bg-raised text-text-primary shadow-sm' : 'text-text-muted hover:text-text-primary')}
            >
              注册
            </button>
          </div>

          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs text-text-secondary">
              <User size={12} />用户名
            </label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="输入用户名"
              autoFocus
              className="w-full rounded-md border border-border bg-bg-surface px-3 py-2 text-sm text-text-primary placeholder-text-muted outline-none transition-colors focus:border-accent"
            />
          </div>

          {mode === 'register' && (
            <div className="space-y-1.5">
              <label className="flex items-center gap-1.5 text-xs text-text-secondary">
                <Mail size={12} />邮箱
              </label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="输入邮箱地址"
                className="w-full rounded-md border border-border bg-bg-surface px-3 py-2 text-sm text-text-primary placeholder-text-muted outline-none transition-colors focus:border-accent"
              />
            </div>
          )}

          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs text-text-secondary">
              <KeyRound size={12} />密码
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="输入密码"
              className="w-full rounded-md border border-border bg-bg-surface px-3 py-2 text-sm text-text-primary placeholder-text-muted outline-none transition-colors focus:border-accent"
            />
          </div>

          {error && (
            <p className="rounded-md border border-accent/20 bg-accent-soft px-3 py-2 text-xs text-accent">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-md bg-accent py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-50"
          >
            {loading ? (
              <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
            ) : mode === 'login' ? (
              <LogIn size={16} />
            ) : (
              <UserPlus size={16} />
            )}
            {mode === 'login' ? '登录' : '注册'}
          </button>

          {isModal && onCancel && (
            <button
              type="button"
              onClick={onCancel}
              className="w-full text-center text-xs text-text-muted transition-colors hover:text-text-secondary"
            >
              稍后再说
            </button>
          )}
        </form>
      </div>
    </div>
  );
}
