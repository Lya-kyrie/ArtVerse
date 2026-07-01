import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, Search, Sparkles } from 'lucide-react';
import {
  API_KEY_CHANGE_EVENT,
  getActiveProviderPreset,
  getApiKeySettings,
  getProviderModelOptions,
  type ApiCapability,
} from '../api';

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface ModelSwitcherProps {
  capability: ApiCapability;
  selectedModel: string;
  onSelect: (model: string) => void;
  disabled?: boolean;
}

interface ProviderMeta {
  label: string;
  emoji: string;
  color: string;
}

interface GroupedModels {
  provider: ProviderMeta;
  models: string[];
}

/* ------------------------------------------------------------------ */
/*  Provider detection                                                 */
/* ------------------------------------------------------------------ */

const PROVIDER_PATTERNS: Array<{ test: (id: string) => boolean; label: string; emoji: string; color: string }> = [
  { test: (id) => id.startsWith('deepseek'),            label: 'DeepSeek',    emoji: '🐋', color: 'bg-blue-100 text-blue-700' },
  { test: (id) => /^gpt-|^o[134]-|^chatgpt/.test(id),   label: 'OpenAI',      emoji: '⚡', color: 'bg-emerald-100 text-emerald-700' },
  { test: (id) => id.startsWith('claude'),               label: 'Claude',      emoji: '🧠', color: 'bg-amber-100 text-amber-700' },
  { test: (id) => id.startsWith('gemini'),               label: 'Gemini',      emoji: '💎', color: 'bg-sky-100 text-sky-700' },
  { test: (id) => id.startsWith('doubao'),               label: '豆包',         emoji: '🫘', color: 'bg-teal-100 text-teal-700' },
  { test: (id) => id.startsWith('qwen'),                 label: 'Qwen',        emoji: '🏔️', color: 'bg-purple-100 text-purple-700' },
  { test: (id) => id.startsWith('grok'),                 label: 'Grok',        emoji: '🚀', color: 'bg-indigo-100 text-indigo-700' },
  { test: (id) => id.startsWith('glm'),                  label: 'GLM',         emoji: '🌐', color: 'bg-cyan-100 text-cyan-700' },
  { test: (id) => id.startsWith('kimi'),                 label: 'Kimi',        emoji: '🌙', color: 'bg-violet-100 text-violet-700' },
  { test: (id) => /minimax|abab/.test(id),               label: 'MiniMax',     emoji: '🔮', color: 'bg-fuchsia-100 text-fuchsia-700' },
  { test: (id) => /seedance|jimeng/.test(id),            label: 'Jimeng',      emoji: '🎬', color: 'bg-rose-100 text-rose-700' },
  { test: (id) => /flux|stable|schnell/.test(id),        label: 'Stability',   emoji: '🖼️', color: 'bg-lime-100 text-lime-700' },
  { test: (id) => /black-forest|midjourney/.test(id),    label: 'Image',       emoji: '🎨', color: 'bg-pink-100 text-pink-700' },
  { test: (id) => id.includes('/'),                      label: 'OpenRouter',  emoji: '🔗', color: 'bg-orange-100 text-orange-700' },
];

function detectProvider(modelId: string): ProviderMeta {
  const lower = modelId.toLowerCase();
  for (const pattern of PROVIDER_PATTERNS) {
    if (pattern.test(lower)) {
      return { label: pattern.label, emoji: pattern.emoji, color: pattern.color };
    }
  }
  return { label: 'Custom', emoji: '🤖', color: 'bg-gray-100 text-gray-700' };
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export default function ModelSwitcher({ capability, selectedModel, onSelect, disabled }: ModelSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [models, setModels] = useState<string[]>(() => getProviderModelOptions(capability));
  const [focusedIndex, setFocusedIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  /* ---- data ---------------------------------------------------- */

  useEffect(() => {
    const sync = () => setModels(getProviderModelOptions(capability));
    sync();
    window.addEventListener(API_KEY_CHANGE_EVENT, sync);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, sync);
  }, [capability]);

  /* ---- open / close -------------------------------------------- */

  const close = useCallback(() => { setOpen(false); setSearch(''); setFocusedIndex(-1); }, []);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) close();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, close]);

  useEffect(() => {
    if (open) {
      const timer = setTimeout(() => searchInputRef.current?.focus(), 80);
      return () => clearTimeout(timer);
    }
  }, [open]);

  /* ---- filtered & grouped -------------------------------------- */

  const filteredModels = useMemo(() => {
    if (!search.trim()) return models;
    const q = search.toLowerCase();
    return models.filter((id) => {
      if (id.toLowerCase().includes(q)) return true;
      return detectProvider(id).label.toLowerCase().includes(q);
    });
  }, [models, search]);

  const groupedModels = useMemo<GroupedModels[]>(() => {
    const groups: Record<string, GroupedModels> = {};
    for (const model of filteredModels) {
      const provider = detectProvider(model);
      if (!groups[provider.label]) {
        groups[provider.label] = { provider, models: [] };
      }
      groups[provider.label].models.push(model);
    }
    // stable sort by provider label
    return Object.values(groups).sort((a, b) => a.provider.label.localeCompare(b.provider.label));
  }, [filteredModels]);

  const flatModelList = useMemo(
    () => groupedModels.flatMap((g) => g.models),
    [groupedModels],
  );

  /* ---- helpers ------------------------------------------------ */

  const updateSearch = useCallback((value: string) => {
    setSearch(value);
    setFocusedIndex(-1);
  }, []);

  /* Scroll focused item into view */
  useEffect(() => {
    if (focusedIndex < 0 || !listRef.current) return;
    const focusedEl = listRef.current.children[focusedIndex] as HTMLElement | undefined;
    focusedEl?.scrollIntoView({ block: 'nearest' });
  }, [focusedIndex]);

  /* ---- derived display values ---------------------------------- */

  const selectedProvider = useMemo(() => detectProvider(selectedModel), [selectedModel]);

  const providerLabel = useMemo(() => {
    try { return getActiveProviderPreset(getApiKeySettings(), capability).label; }
    catch { return selectedProvider.label; }
  }, [capability, selectedProvider.label]);

  const triggerLabel = selectedModel ? selectedProvider.label : null;

  /* ---- keyboard navigation ------------------------------------- */

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!open) return;

    switch (e.key) {
      case 'ArrowDown': {
        e.preventDefault();
        setFocusedIndex((prev) => (prev + 1 < flatModelList.length ? prev + 1 : 0));
        break;
      }
      case 'ArrowUp': {
        e.preventDefault();
        setFocusedIndex((prev) => (prev - 1 >= 0 ? prev - 1 : flatModelList.length - 1));
        break;
      }
      case 'Enter': {
        e.preventDefault();
        if (focusedIndex >= 0 && focusedIndex < flatModelList.length) {
          onSelect(flatModelList[focusedIndex]);
          close();
        }
        break;
      }
      case 'Escape': {
        e.preventDefault();
        close();
        break;
      }
    }
  };

  /* ---- search input keydown (Enter selects first match) --------- */
  const handleSearchKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { close(); return; }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      // Move focus from search input to list
      handleKeyDown(e);
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (flatModelList.length === 1) {
        onSelect(flatModelList[0]);
        close();
      } else if (focusedIndex >= 0 && focusedIndex < flatModelList.length) {
        onSelect(flatModelList[focusedIndex]);
        close();
      }
    }
  };

  /* ---- render -------------------------------------------------- */

  return (
    <div ref={containerRef} className="relative inline-flex items-center" onKeyDown={handleKeyDown}>
      {/* ── Trigger ── */}
      <button
        type="button"
        disabled={disabled}
        onClick={() => { if (!disabled) setOpen((prev) => !prev); }}
        className={
          'inline-flex h-7 items-center gap-1.5 rounded-lg border px-2 text-sm font-medium transition-all duration-200 ' +
          (open
            ? 'border-vermilion/40 bg-vermilion-light/20 text-vermilion shadow-sm'
            : 'border-paper-border bg-paper-surface/80 text-sumi-dim hover:border-sumi-faint/40 hover:bg-paper-base hover:text-sumi') +
          (disabled ? ' cursor-not-allowed opacity-50' : ' cursor-pointer')
        }
        title={selectedModel ? `${selectedModel}\n${providerLabel}` : '选择模型'}
      >
        {triggerLabel ? (
          <>
            <span className="text-[12px] leading-none" aria-hidden>{selectedProvider.emoji}</span>
            <span className="text-[12px]">{triggerLabel}</span>
          </>
        ) : (
          <>
            <Sparkles size={12} className="text-sumi-faint" />
            <span className="text-[12px] text-sumi-faint">选择模型</span>
          </>
        )}
        <ChevronDown
          size={12}
          className={`shrink-0 text-sumi-faint transition-transform duration-200 ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {/* ── Dropdown ── */}
      {open && (
        <div className="absolute right-0 bottom-[calc(100%+6px)] z-30 flex w-[300px] flex-col overflow-hidden rounded-xl border border-paper-border/80 bg-paper-raised shadow-lg animate-fade-in">
          {/* Search */}
          <div className="flex items-center gap-2 border-b border-paper-border/60 px-3 py-2.5">
            <Search size={14} className="shrink-0 text-sumi-faint" />
            <input
              ref={searchInputRef}
              type="text"
              value={search}
              onChange={(e) => updateSearch(e.target.value)}
              onKeyDown={handleSearchKeyDown}
              placeholder="搜索模型..."
              className="min-w-0 flex-1 bg-transparent text-[13px] text-sumi placeholder:text-sumi-faint/60 outline-none"
            />
            {search && (
              <button
                type="button"
                onClick={() => updateSearch('')}
                className="rounded p-0.5 text-sumi-faint hover:text-sumi-dim transition-colors"
                aria-label="清除搜索"
              >
                <span className="text-[11px]">✕</span>
              </button>
            )}
          </div>

          {/* Model list */}
          <div ref={listRef} className="max-h-[280px] overflow-y-auto overscroll-contain p-2">
            {groupedModels.length === 0 ? (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <Search size={20} className="text-sumi-faint/30" />
                <p className="text-[13px] text-sumi-faint">
                  {search ? '没有匹配的模型' : '暂无可选模型'}
                </p>
                {search && (
                  <button
                    type="button"
                    onClick={() => updateSearch('')}
                    className="text-[12px] text-vermilion hover:text-vermilion-hover transition-colors"
                  >
                    清除搜索
                  </button>
                )}
              </div>
            ) : (
              groupedModels.map((group) => (
                <div key={group.provider.label} className="mb-0.5 last:mb-0">
                  {/* Group header */}
                  <div className="flex items-center gap-1.5 px-2 py-1.5">
                    <span
                      className={`flex h-5 w-5 shrink-0 items-center justify-center rounded text-[11px] leading-none ${group.provider.color}`}
                      aria-hidden
                    >
                      {group.provider.emoji}
                    </span>
                    <span className="text-[11px] font-medium uppercase tracking-wider text-sumi-faint/80">
                      {group.provider.label}
                    </span>
                    <span className="text-[10px] text-sumi-faint/50">{group.models.length}</span>
                  </div>
                  {/* Group items */}
                  {group.models.map((modelId) => {
                    const selected = modelId === selectedModel;
                    const flatIdx = flatModelList.indexOf(modelId);
                    const focused = flatIdx === focusedIndex;
                    return (
                      <button
                        key={modelId}
                        type="button"
                        onClick={() => { onSelect(modelId); close(); }}
                        onMouseEnter={() => setFocusedIndex(flatIdx)}
                        className={
                          'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left transition-colors duration-100 ' +
                          (selected
                            ? 'bg-vermilion-light/20 text-vermilion'
                            : focused
                              ? 'bg-paper-surface text-sumi'
                              : 'text-sumi hover:bg-paper-surface')
                        }
                      >
                        <span className="truncate text-[13px] font-medium">{modelId}</span>
                        {selected && <Check size={14} className="ml-auto shrink-0 text-vermilion" />}
                      </button>
                    );
                  })}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
