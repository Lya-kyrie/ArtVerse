import { Check, Moon, Palette, Sun } from 'lucide-react';
import { useState, useRef, useEffect } from 'react';
import { useTheme, THEMES } from '../hooks/useTheme';

interface Props {
  compact?: boolean;
}

export default function ThemeToggle({ compact = false }: Props) {
  const { themeId, mode, currentTheme, setTheme, toggleMode } = useTheme();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      {/* Trigger */}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        title="更换配色主题"
        className={
          'group flex items-center gap-3 rounded-lg text-sm font-medium transition-all duration-200 '
          + 'text-text-secondary hover:text-accent hover:bg-accent-soft '
          + (compact ? 'justify-center h-9 w-9 p-0' : 'min-h-10 w-full px-3')
        }
      >
        <Palette size={18} className="shrink-0" />
        {!compact && (
          <>
            <span className="flex-1 text-left">{currentTheme.emoji} {currentTheme.name}</span>
            <span className="text-[10px] text-text-muted">{mode === 'dark' ? '暗' : '浅'}</span>
          </>
        )}
      </button>

      {/* Dropdown */}
      {open && (
        <div className="absolute bottom-full left-0 mb-2 w-56 animate-fade-in z-50">
          <div className="rounded-xl border border-border bg-bg-raised shadow-xl p-1.5">
            {/* Theme list */}
            <p className="px-2.5 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-text-muted">配色主题</p>
            {THEMES.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => { setTheme(t.id); }}
                className={
                  'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm transition-colors '
                  + (themeId === t.id ? 'bg-accent-muted text-accent' : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary')
                }
              >
                <span className="text-base">{t.emoji}</span>
                <span className="flex-1 text-left font-medium">{t.name}</span>
                {themeId === t.id && <Check size={14} className="text-accent shrink-0" />}
              </button>
            ))}

            {/* Divider + mode toggle */}
            <div className="mx-2 my-1 border-t border-border" />
            <button
              type="button"
              onClick={toggleMode}
              className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-text-secondary hover:bg-accent-soft hover:text-text-primary transition-colors"
            >
              {mode === 'dark'
                ? <Sun size={15} className="shrink-0" />
                : <Moon size={15} className="shrink-0" />
              }
              <span>{mode === 'dark' ? '切换浅色模式' : '切换深色模式'}</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
