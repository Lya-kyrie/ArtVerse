import type { ReactNode } from 'react';

interface Props {
  children: ReactNode;
  className?: string;
  onClick?: () => void;
  /** When true, card is interactive with hover effects. Default true. */
  interactive?: boolean;
}

export default function BentoCard({ children, className = '', onClick, interactive = true }: Props) {
  return (
    <div
      onClick={onClick}
      onKeyDown={onClick ? (e) => { if (e.key === 'Enter') onClick(); } : undefined}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      className={`bento-card overflow-hidden ${interactive ? 'cursor-pointer' : ''} ${className}`}
    >
      {children}
    </div>
  );
}
