import type { CSSProperties, ReactNode } from 'react';

interface Props {
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
  /** Extra blur intensity. Default 16px. */
  blur?: number;
}

export default function GlassPanel({ children, className = '', style, blur = 16 }: Props) {
  return (
    <div
      className={`glass-panel rounded-xl ${className}`}
      style={{
        ...style,
        backdropFilter: `blur(${blur}px) saturate(120%)`,
        WebkitBackdropFilter: `blur(${blur}px) saturate(120%)`,
      }}
    >
      {children}
    </div>
  );
}
