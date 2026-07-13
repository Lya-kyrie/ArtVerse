interface Props {
  className?: string;
  /** Preset shapes. Default 'text'. */
  variant?: 'text' | 'circular' | 'rectangular' | 'card';
}

const variantClass: Record<string, string> = {
  text: 'h-4 w-full rounded',
  circular: 'h-10 w-10 rounded-full',
  rectangular: 'h-24 w-full rounded-lg',
  card: 'h-48 w-full rounded-xl',
};

export default function Skeleton({ className = '', variant = 'text' }: Props) {
  return (
    <div
      className={`shimmer-bg animate-pulse bg-bg-surface ${variantClass[variant]} ${className}`}
      aria-hidden="true"
    />
  );
}
