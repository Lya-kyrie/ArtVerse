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
    <div className={(isModal ? '' : 'min-h-screen bg-paper-base ') + 'flex items-center justify-center p-4'}>
      <div className="w-full max-w-sm">
        <div className="mb-4 flex items-center justify-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-vermilion text-white shadow-sm">
            <Sparkles size={20} />
          </div>
          <div className="text-left">
            <h1 className="font-display text-lg font-bold text-sumi">ArtVerse</h1>
            <p className="text-xs text-sumi-faint">AI 漫画创作工坊</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 rounded-lg border border-paper-border bg-paper-raised p-6 shadow-[0_18px_60px_rgba(24,27,25,0.16)]">
          {message && (
            <p className="rounded-md border border-kinpaku/20 bg-kinpaku-light/50 px-3 py-2 text-sm text-kinpaku">
              {message}
            </p>
          )}

          <div className="grid grid-cols-2 gap-1 rounded-md bg-paper-surface p-1" aria-label="账号操作">
            <button
              type="button"
              onClick={() => { setMode('login'); setError(''); }}
              className={'rounded px-3 py-2 text-xs font-medium transition-colors ' + (mode === 'login' ? 'bg-paper-raised text-sumi shadow-sm' : 'text-sumi-faint hover:text-sumi')}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => { setMode('register'); setError(''); }}
              className={'rounded px-3 py-2 text-xs font-medium transition-colors ' + (mode === 'register' ? 'bg-paper-raised text-sumi shadow-sm' : 'text-sumi-faint hover:text-sumi')}
            >
              注册
            </button>
          </div>

          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs text-sumi-dim">
              <User size={12} />用户名
            </label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="输入用户名"
              autoFocus
              className="w-full rounded-md border border-paper-border bg-paper-surface px-3 py-2 text-sm text-sumi placeholder-sumi-faint outline-none transition-colors focus:border-vermilion"
            />
          </div>

          {mode === 'register' && (
            <div className="space-y-1.5">
              <label className="flex items-center gap-1.5 text-xs text-sumi-dim">
                <Mail size={12} />邮箱
              </label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="输入邮箱地址"
                className="w-full rounded-md border border-paper-border bg-paper-surface px-3 py-2 text-sm text-sumi placeholder-sumi-faint outline-none transition-colors focus:border-vermilion"
              />
            </div>
          )}

          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs text-sumi-dim">
              <KeyRound size={12} />密码
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="输入密码"
              className="w-full rounded-md border border-paper-border bg-paper-surface px-3 py-2 text-sm text-sumi placeholder-sumi-faint outline-none transition-colors focus:border-vermilion"
            />
          </div>

          {error && (
            <p className="rounded-md border border-vermilion/20 bg-vermilion-light/20 px-3 py-2 text-xs text-vermilion">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-md bg-vermilion py-2.5 text-sm font-medium text-white transition-colors hover:bg-vermilion-hover disabled:opacity-50"
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
              className="w-full text-center text-xs text-sumi-faint transition-colors hover:text-sumi-dim"
            >
              稍后再说
            </button>
          )}
        </form>
      </div>
    </div>
  );
}
