import { Loader2 } from 'lucide-react';

interface Props {
  /** Descriptive label for the streaming state. */
  label?: string;
  className?: string;
}

export default function StreamBubble({ label = '生成中...', className = '' }: Props) {
  return (
    <div
      className={`inline-flex items-center gap-2 rounded-2xl border border-border bg-bg-surface px-4 py-2.5 text-sm text-text-secondary ${className}`}
    >
      <Loader2 size={14} className="animate-spin text-accent" />
      <span>{label}</span>
      <span className="flex gap-0.5 pt-0.5">
        <span className="h-1 w-1 animate-pulse rounded-full bg-accent [animation-delay:0ms]" />
        <span className="h-1 w-1 animate-pulse rounded-full bg-accent [animation-delay:150ms]" />
        <span className="h-1 w-1 animate-pulse rounded-full bg-accent [animation-delay:300ms]" />
      </span>
    </div>
  );
}
