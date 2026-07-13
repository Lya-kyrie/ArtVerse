import type { ButtonHTMLAttributes, ReactNode } from 'react';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  children: ReactNode;
  /** Visual variant. Default 'primary'. */
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
}

const variantClass: Record<string, string> = {
  primary:
    'bg-accent text-white hover:bg-accent-hover shadow-glow active:scale-[0.97]',
  secondary:
    'bg-accent-muted text-accent hover:bg-accent hover:text-white border border-accent/20',
  ghost:
    'bg-transparent text-text-secondary hover:bg-accent-soft hover:text-accent',
  danger:
    'bg-danger text-white hover:opacity-90 active:scale-[0.97]',
};

const sizeClass: Record<string, string> = {
  sm: 'h-8 px-3 text-xs rounded-md',
  md: 'h-10 px-4 text-sm rounded-lg',
  lg: 'h-12 px-6 text-base rounded-xl',
};

export default function AccentButton({
  children,
  variant = 'primary',
  size = 'md',
  loading = false,
  className = '',
  disabled,
  ...rest
}: Props) {
  return (
    <button
      className={`inline-flex items-center justify-center gap-2 font-medium transition-all duration-200
        focus-visible:outline-2 focus-visible:outline-accent
        disabled:cursor-not-allowed disabled:opacity-40
        ${variantClass[variant]} ${sizeClass[size]} ${className}`}
      disabled={disabled || loading}
      {...rest}
    >
      {loading && (
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
      )}
      {children}
    </button>
  );
}
