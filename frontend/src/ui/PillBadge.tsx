import type { ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** Visual tone. Default 'neutral'. */
  tone?: 'neutral' | 'accent' | 'secondary' | 'tertiary' | 'success' | 'warning' | 'danger';
  className?: string;
}

const toneClass: Record<string, string> = {
  neutral: 'bg-bg-surface text-text-secondary border-border',
  accent: 'bg-accent-muted text-accent border-accent/20',
  secondary: 'bg-pink-500/10 text-accent-secondary border-pink-500/20',
  tertiary: 'bg-cyan-500/10 text-accent-tertiary border-cyan-500/20',
  success: 'bg-green-500/10 text-success border-green-500/20',
  warning: 'bg-yellow-500/10 text-warning border-yellow-500/20',
  danger: 'bg-red-500/10 text-danger border-red-500/20',
};

export default function PillBadge({ children, tone = 'neutral', className = '' }: Props) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-medium ${toneClass[tone]} ${className}`}
    >
      {children}
    </span>
  );
}
