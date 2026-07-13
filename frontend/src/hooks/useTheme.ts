import { useCallback, useEffect, useState } from 'react';

export type ThemeId = 'inknight' | 'terracotta' | 'deepblue' | 'verdant';
type Mode = 'dark' | 'light';

interface ThemeMeta {
  id: ThemeId;
  name: string;
  emoji: string;
  description: string;
}

export const THEMES: ThemeMeta[] = [
  { id: 'inknight', name: '墨夜', emoji: '🌙', description: '传统水墨 × 现代暗色工作室' },
  { id: 'terracotta', name: '茜空', emoji: '🍂', description: '温暖泥土色调的创作空间' },
  { id: 'deepblue', name: '绀青', emoji: '🌊', description: '专业深蓝工具风格' },
  { id: 'verdant', name: '翠青', emoji: '🌿', description: '清新自然的竹林创作空间' },
];

/** Map old theme IDs to new ones so existing users migrate seamlessly. */
const MIGRATE: Record<string, ThemeId> = {
  emerald: 'inknight',
  crimson: 'terracotta',
  cobalt: 'deepblue',
  amber: 'verdant',
  mono: 'inknight',
  gallery: 'inknight',
};

const KEY_THEME = 'artverse.themeId';
const KEY_MODE = 'artverse.mode';

function getStoredTheme(): ThemeId {
  try {
    const v = localStorage.getItem(KEY_THEME);
    if (v) {
      if (THEMES.some(t => t.id === v)) return v as ThemeId;
      if (MIGRATE[v]) return MIGRATE[v];
    }
  } catch { /* noop */ }
  return 'inknight';
}

function getSystemMode(): Mode {
  if (typeof window === 'undefined') return 'dark';
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function getStoredMode(): Mode | null {
  try {
    const v = localStorage.getItem(KEY_MODE);
    if (v === 'dark' || v === 'light') return v;
  } catch { /* noop */ }
  return null;
}

function apply(theme: ThemeId, mode: Mode) {
  document.documentElement.setAttribute('data-theme', theme);
  document.documentElement.setAttribute('data-mode', mode);
}

export function useTheme() {
  const [themeId, setThemeIdState] = useState<ThemeId>(getStoredTheme);
  const [mode, setModeState] = useState<Mode>(() => getStoredMode() ?? getSystemMode());

  useEffect(() => { apply(themeId, mode); }, [themeId, mode]);

  // Track system preference when no stored mode
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: light)');
    const handler = (e: MediaQueryListEvent) => {
      if (!getStoredMode()) setModeState(e.matches ? 'light' : 'dark');
    };
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  const setTheme = useCallback((t: ThemeId) => {
    setThemeIdState(t);
    try { localStorage.setItem(KEY_THEME, t); } catch { /* noop */ }
  }, []);

  const setMode = useCallback((m: Mode) => {
    setModeState(m);
    try { localStorage.setItem(KEY_MODE, m); } catch { /* noop */ }
  }, []);

  const toggleMode = useCallback(() => {
    setModeState(prev => {
      const next: Mode = prev === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(KEY_MODE, next); } catch { /* noop */ }
      return next;
    });
  }, []);

  const currentTheme = THEMES.find(t => t.id === themeId)!;

  return { themeId, mode, currentTheme, setTheme, setMode, toggleMode, allThemes: THEMES };
}
