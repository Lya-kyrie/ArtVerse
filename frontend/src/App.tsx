import { useEffect, useState } from 'react';
import {
  BookOpenText,
  Bot,
  Check,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  FileText,
  Globe,
  Image,
  KeyRound,
  LogIn,
  LogOut,
  MessageSquare,
  Paintbrush,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Sparkles,
  Trash2,
  Workflow,
  X,
} from 'lucide-react';
import ChatPanel from './components/ChatPanel';
import MangaPanel from './components/MangaPanel';
import MangaAgentPage from './components/MangaAgentPage';
import HomePage from './components/HomePage';
import LoginPage from './components/LoginPage';
import SquarePage from './components/SquarePage';
import ImageGenPage from './components/ImageGenPage';
import MyWorksPage from './components/MyWorksPage';
import {
  listChapters,
  createNextChapter,
  deleteChapter,
  getChapter,
  type ApiCapability,
  type ApiKeySettings,
  type CapabilityProviderSettings,
  type Chapter,
  type ProviderPresetConfig,
  clearApiKeySettings,
  discoverProviderModels,
  getApiKeySettings,
  getActiveProviderPreset,
  getUserProviderConfigs,
  saveApiKeySettings,
  saveUserProviderConfig,
  toProviderEndpointConfig,
  isAuthenticated,
  logoutUser,
} from './api';

type View = 'home' | 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks';
type MobileTab = 'chat' | 'manga';

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';
const MOBILE_BREAKPOINT = 1024;
type ProviderPreset = {
  id: string;
  label: string;
  docsUrl?: string;
  baseUrl: string;
  models: string[];
};

const PROVIDER_PRESETS: Record<ApiCapability, ProviderPreset[]> = {
  llm: [
    { id: 'deepseek', label: 'DeepSeek Official', docsUrl: 'https://platform.deepseek.com/usage', baseUrl: 'https://api.deepseek.com', models: ['deepseek-v4-flash', 'deepseek-chat'] },
    { id: 'openai', label: 'OpenAI Official', docsUrl: 'https://platform.openai.com/api-keys', baseUrl: 'https://api.openai.com/v1', models: ['gpt-4.1-mini', 'gpt-4.1'] },
    { id: 'openrouter', label: 'OpenRouter', docsUrl: 'https://openrouter.ai/keys', baseUrl: 'https://openrouter.ai/api/v1', models: ['openai/gpt-4.1-mini', 'anthropic/claude-3.7-sonnet'] },
    { id: 'siliconflow', label: 'SiliconFlow', docsUrl: 'https://cloud.siliconflow.cn/account/ak', baseUrl: 'https://api.siliconflow.cn/v1', models: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen3-32B'] },
    { id: 'qwen', label: 'Qwen Bailian', docsUrl: 'https://bailian.console.aliyun.com/', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', models: ['qwen-plus', 'qwen-max'] },
    { id: 'ark', label: 'Volcengine Ark', docsUrl: 'https://console.volcengine.com/ark', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', models: ['doubao-seed-1-6-flash-250615', 'doubao-1-5-pro-32k-250115'] },
    { id: 'custom', label: 'Custom OpenAI-Compatible', baseUrl: 'https://your-gateway.example.com/v1', models: ['your-model-name'] },
  ],
  image: [
    { id: 'image2', label: 'Image2 Official', docsUrl: 'https://api.duojie.games/console/token', baseUrl: 'https://api.duojie.games/v1', models: ['gpt-image-2'] },
    { id: 'openai-images', label: 'OpenAI Images', docsUrl: 'https://platform.openai.com/api-keys', baseUrl: 'https://api.openai.com/v1', models: ['gpt-image-1'] },
    { id: 'openrouter-images', label: 'OpenRouter Images', docsUrl: 'https://openrouter.ai/keys', baseUrl: 'https://openrouter.ai/api/v1', models: ['openai/gpt-image-1'] },
    { id: 'siliconflow-images', label: 'SiliconFlow Images', docsUrl: 'https://cloud.siliconflow.cn/account/ak', baseUrl: 'https://api.siliconflow.cn/v1', models: ['black-forest-labs/FLUX.1-schnell', 'stabilityai/stable-image-ultra'] },
    { id: 'custom', label: 'Custom Image Gateway', baseUrl: 'https://your-image-gateway.example.com/v1', models: ['your-image-model'] },
  ],
  workflow: [
    { id: 'coze', label: 'Coze Official', docsUrl: 'https://www.coze.cn/open/docs/developer_guides/pat', baseUrl: 'https://api.coze.cn', models: ['workflow'] },
    { id: 'dify', label: 'Dify Workflow', docsUrl: 'https://cloud.dify.ai/apps', baseUrl: 'https://api.dify.ai/v1', models: ['workflow'] },
    { id: 'custom', label: 'Custom Workflow Gateway', baseUrl: 'https://your-workflow.example.com/v1', models: ['workflow-or-agent'] },
  ],
};

function useIsMobile() {
  const read = () =>
    navigator.maxTouchPoints > 0 ||
    window.matchMedia('(any-pointer:coarse)').matches ||
    window.matchMedia('(max-width:' + MOBILE_BREAKPOINT + 'px)').matches ||
    window.matchMedia('(pointer:coarse)').matches;
  const [m, setM] = useState(read);
  useEffect(() => {
    const w = window.matchMedia('(max-width:' + MOBILE_BREAKPOINT + 'px)');
    const t = window.matchMedia('(pointer:coarse)');
    const a = window.matchMedia('(any-pointer:coarse)');
    let f = 0;
    const s = () => {
      cancelAnimationFrame(f);
      f = requestAnimationFrame(() => setM(read()));
    };
    w.addEventListener('change', s);
    t.addEventListener('change', s);
    a.addEventListener('change', s);
    window.addEventListener('resize', s);
    return () => {
      cancelAnimationFrame(f);
      w.removeEventListener('change', s);
      t.removeEventListener('change', s);
      a.removeEventListener('change', s);
      window.removeEventListener('resize', s);
    };
  }, []);
  return m;
}

function clearWorkspaceState() {
  localStorage.removeItem(LS_STORY_ID);
  localStorage.removeItem(LS_CHAPTER_ID);
  localStorage.removeItem(LS_CHAPTER_IDX);
}

function cloneSettings(settings: ApiKeySettings): ApiKeySettings {
  return JSON.parse(JSON.stringify(settings)) as ApiKeySettings;
}

function mergeServerProviderConfigs(local: ApiKeySettings, remote: Partial<Record<ApiCapability, { presetId: string; label: string; apiKey: string; baseUrl: string; model: string }>>): ApiKeySettings {
  const next = cloneSettings(local);
  (['llm', 'image', 'workflow'] as ApiCapability[]).forEach((capability) => {
    const remoteConfig = remote[capability];
    if (!remoteConfig) return;
    const presetId = next.providers[capability].presets[remoteConfig.presetId] ? remoteConfig.presetId : 'custom';
    const preset = next.providers[capability].presets[presetId];
    next.providers[capability].activePresetId = presetId;
    preset.label = remoteConfig.label || preset.label;
    preset.baseUrl = remoteConfig.baseUrl || preset.baseUrl;
    preset.selectedModels = remoteConfig.model
      ? remoteConfig.model.split(/[\n,]+/).map((item) => item.trim()).filter(Boolean)
      : preset.selectedModels;
    preset.availableModels = Array.from(new Set([...preset.availableModels, ...preset.selectedModels]));
    preset.mode = presetId === 'custom' ? 'custom' : 'official';
  });
  return next;
}

function ApiPresetMenu({
  presets,
  activePresetId,
  open,
  onToggle,
  onSelect,
}: {
  presets: ProviderPreset[];
  activePresetId: string;
  open: boolean;
  onToggle: () => void;
  onSelect: (presetId: string) => void;
}) {
  const activePreset = presets.find((preset) => preset.id === activePresetId) || presets[0];
  return (
    <div className="relative">
      <button
        type="button"
        onClick={onToggle}
        className={'flex w-full items-center justify-between gap-3 rounded-2xl border bg-white/80 px-3 py-3 text-left transition-colors ' + (open ? 'border-vermilion bg-vermilion-light/10' : 'border-paper-border hover:border-sumi-faint')}
      >
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-sumi">{activePreset.label}</div>
          <div className="mt-1 truncate text-[11px] text-sumi-faint">{activePreset.baseUrl}</div>
        </div>
        <div className="flex items-center gap-2">
          <div className="rounded-full border border-paper-border bg-paper-base px-2 py-1 text-[11px] text-sumi-dim">
            {presets.length} 个选项
          </div>
          <ChevronRight size={16} className={'shrink-0 text-sumi-faint transition-transform ' + (open ? 'rotate-90' : '')} />
        </div>
      </button>
      {open ? (
        <div className="absolute left-0 right-0 top-[calc(100%+8px)] z-20 max-h-72 overflow-y-auto rounded-2xl border border-paper-border bg-paper-raised p-2 shadow-xl">
          {presets.map((preset) => {
            const selected = preset.id === activePresetId;
            return (
              <button
                key={preset.id}
                type="button"
                onClick={() => onSelect(preset.id)}
                className={'mb-1 flex w-full items-start justify-between gap-3 rounded-xl px-3 py-2.5 text-left transition-colors last:mb-0 ' + (selected ? 'bg-vermilion-light/20 text-vermilion' : 'bg-white/70 text-sumi hover:bg-paper-base')}
              >
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium">{preset.label}</div>
                  <div className={'mt-1 truncate text-[11px] ' + (selected ? 'text-vermilion/80' : 'text-sumi-faint')}>{preset.baseUrl}</div>
                </div>
                <div className={'mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border ' + (selected ? 'border-vermilion bg-vermilion text-white' : 'border-paper-border bg-white text-transparent')}>
                  <Check size={12} />
                </div>
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

function ModelChecklist({
  models,
  selectedModels,
  onToggle,
}: {
  models: string[];
  selectedModels: string[];
  onToggle: (modelId: string) => void;
}) {
  if (models.length === 0) {
    return <div className="rounded-xl border border-dashed border-paper-border bg-white/70 px-3 py-4 text-sm text-sumi-faint">还没有可选模型，先填写地址和 Key 后点击“拉取模型”。</div>;
  }
  return (
    <div className="grid max-h-60 gap-2 overflow-y-auto rounded-xl border border-paper-border bg-white/80 p-2.5">
      {models.map((modelId) => {
        const checked = selectedModels.includes(modelId);
        return (
          <label key={modelId} className={'flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2 transition-colors ' + (checked ? 'border-vermilion/30 bg-vermilion-light/20 text-vermilion' : 'border-paper-border bg-paper-base text-sumi hover:border-sumi-faint')}>
            <input type="checkbox" checked={checked} onChange={() => onToggle(modelId)} className="h-4 w-4 rounded border-paper-border text-vermilion focus:ring-vermilion" />
            <span className="break-all text-sm">{modelId}</span>
          </label>
        );
      })}
    </div>
  );
}

function ApiKeySettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [settings, setSettings] = useState<ApiKeySettings>(() => getApiKeySettings());
  const [saving, setSaving] = useState(false);
  const [loadingRemote, setLoadingRemote] = useState(false);
  const [error, setError] = useState('');
  const [loadingModels, setLoadingModels] = useState<Record<string, boolean>>({});
  const [openPresetMenu, setOpenPresetMenu] = useState<ApiCapability | null>(null);

  useEffect(() => {
    if (!open) return;
    const local = getApiKeySettings();
    setSettings(local);
    setError('');
    setOpenPresetMenu(null);
    setLoadingRemote(true);
    getUserProviderConfigs()
      .then((remote) => {
        const merged = mergeServerProviderConfigs(local, remote);
        setSettings(merged);
        saveApiKeySettings(merged);
      })
      .catch(() => undefined)
      .finally(() => setLoadingRemote(false));
  }, [open]);

  if (!open) return null;

  const updateCapability = (capability: ApiCapability, updater: (current: CapabilityProviderSettings) => CapabilityProviderSettings) => {
    setSettings((prev) => ({
      ...prev,
      providers: {
        ...prev.providers,
        [capability]: updater(prev.providers[capability]),
      },
    }));
  };

  const selectPreset = (capability: ApiCapability, presetId: string) => {
    const preset = PROVIDER_PRESETS[capability].find((item) => item.id === presetId);
    if (!preset) return;
    updateCapability(capability, (current) => {
      const next = JSON.parse(JSON.stringify(current)) as CapabilityProviderSettings;
      const target = next.presets[preset.id] || next.presets.custom;
      target.presetId = preset.id;
      target.label = preset.label;
      target.baseUrl = preset.baseUrl;
      target.mode = preset.id === 'custom' ? 'custom' : 'official';
      target.availableModels = Array.from(new Set([...target.availableModels, ...preset.models]));
      if (target.selectedModels.length === 0) target.selectedModels = [...preset.models];
      next.activePresetId = preset.id;
      return next;
    });
    setOpenPresetMenu(null);
  };

  const updateActivePreset = (capability: ApiCapability, patch: Partial<ProviderPresetConfig>) => {
    updateCapability(capability, (current) => {
      const next = JSON.parse(JSON.stringify(current)) as CapabilityProviderSettings;
      const activePreset = next.presets[next.activePresetId];
      next.presets[next.activePresetId] = {
        ...activePreset,
        ...patch,
      };
      return next;
    });
  };

  const toggleModel = (capability: ApiCapability, modelId: string) => {
    updateActivePreset(capability, {
      selectedModels: (() => {
        const active = getActiveProviderPreset(settings, capability);
        return active.selectedModels.includes(modelId)
          ? active.selectedModels.filter((item) => item !== modelId)
          : [...active.selectedModels, modelId];
      })(),
    });
  };

  const loadModels = async (capability: ApiCapability) => {
    const active = getActiveProviderPreset(settings, capability);
    const cacheKey = `${capability}:${active.presetId}`;
    setLoadingModels((prev) => ({ ...prev, [cacheKey]: true }));
    setError('');
    try {
      const discovered = await discoverProviderModels(capability, toProviderEndpointConfig(active));
      updateActivePreset(capability, {
        availableModels: Array.from(new Set([...discovered, ...active.availableModels, ...active.selectedModels])),
        selectedModels: active.selectedModels.length > 0
          ? active.selectedModels
          : discovered.slice(0, 1),
      });
    } catch (e: any) {
      setError(e?.message || '拉取模型失败');
    } finally {
      setLoadingModels((prev) => ({ ...prev, [cacheKey]: false }));
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setError('');
    try {
      saveApiKeySettings(settings);
      await Promise.all(
        (['llm', 'image', 'workflow'] as ApiCapability[]).map((capability) =>
          saveUserProviderConfig(capability, toProviderEndpointConfig(getActiveProviderPreset(settings, capability))),
        ),
      );
      onClose();
    } catch (e: any) {
      setError(e?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const cards: Array<{ capability: ApiCapability; title: string; description: string; icon: React.ReactNode }> = [
    { capability: 'llm', title: '对话 / 小说', description: '文本生成与兼容 OpenAI 的大模型网关。', icon: <Bot size={16} className="text-vermilion" /> },
    { capability: 'image', title: 'Image', description: '官方图像接口或自定义图像网关。', icon: <Image size={16} className="text-aizuri" /> },
    { capability: 'workflow', title: '工作流 / Agent', description: 'Coze、Dify 或自定义工作流网关。', icon: <Workflow size={16} className="text-kinpaku" /> },
  ];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-sumi/40 backdrop-blur-sm" onClick={onClose}>
      <div className="m-4 flex h-[calc(100dvh-2rem)] w-full max-w-6xl flex-col overflow-hidden rounded-2xl border border-paper-border bg-paper-raised shadow-modal animate-fade-in" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-6 pt-6">
          <h2 className="flex items-center gap-2 text-lg font-semibold text-sumi">
            <KeyRound size={18} className="text-kinpaku" />
            API 设置
          </h2>
          <button onClick={onClose} className="text-sumi-faint hover:text-sumi-dim transition-colors" aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        <div className="mt-5 min-h-0 flex-1 overflow-y-auto px-6 pb-4 pr-5">
          {loadingRemote ? <div className="rounded-xl border border-paper-border bg-paper-surface px-4 py-3 text-sm text-sumi-dim">正在读取后端已保存的 API 配置…</div> : null}
          {error ? <div className="mt-4 rounded-xl border border-vermilion/20 bg-vermilion-light/20 px-4 py-3 text-sm text-vermilion">{error}</div> : null}

          <div className="mt-5 grid gap-4 xl:grid-cols-3">
            {cards.map(({ capability, title, description, icon }) => {
              const provider = settings.providers[capability];
              const presets = PROVIDER_PRESETS[capability];
              const activePreset = getActiveProviderPreset(settings, capability);
              const selectedPreset = presets.find((item) => item.id === provider.activePresetId) || presets[0];
              const modelLoadingKey = `${capability}:${activePreset.presetId}`;
              return (
                <div key={capability} className="rounded-2xl border border-paper-border bg-paper-surface p-4 shadow-sm">
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div>
                      <div className="flex items-center gap-2 text-sm font-medium text-sumi">{icon}{title}</div>
                      <p className="mt-1 text-xs leading-5 text-sumi-dim">{description}</p>
                    </div>
                    <span className="rounded-full border border-paper-border bg-white/80 px-2 py-1 text-[11px] text-sumi-dim">{activePreset.mode === 'custom' ? '自定义' : '官方'}</span>
                  </div>
                  <div className="space-y-3">
                    <div>
                      <label className="mb-2 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">预设服务商</label>
                      <ApiPresetMenu
                        presets={presets}
                        activePresetId={provider.activePresetId}
                        open={openPresetMenu === capability}
                        onToggle={() => setOpenPresetMenu((current) => current === capability ? null : capability)}
                        onSelect={(presetId) => selectPreset(capability, presetId)}
                      />
                    </div>
                    <div>
                      <label className="mb-2 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">接入方式</label>
                      <div className="grid grid-cols-2 gap-2">
                        {(['official', 'custom'] as const).map((mode) => (
                          <button
                            key={mode}
                            type="button"
                            onClick={() => updateActivePreset(capability, { mode })}
                            className={'rounded-xl border px-3 py-2 text-sm transition-colors ' + (activePreset.mode === mode ? 'border-vermilion bg-vermilion-light/20 text-vermilion' : 'border-paper-border bg-white/80 text-sumi-dim hover:text-sumi')}
                          >
                            {mode === 'official' ? '官方 API' : '自定义 API'}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div>
                      <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">API Key</label>
                      <input type="password" value={activePreset.apiKey} onChange={(e) => updateActivePreset(capability, { apiKey: e.target.value })} placeholder="sk-... / pat-..." className="w-full rounded-xl border border-paper-border bg-white/85 px-3 py-2 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none transition-colors" />
                    </div>
                    <div>
                      <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">Base URL</label>
                      <input type="text" value={activePreset.baseUrl} onChange={(e) => updateActivePreset(capability, { baseUrl: e.target.value })} placeholder="https://api.example.com/v1" className="w-full rounded-xl border border-paper-border bg-white/85 px-3 py-2 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none transition-colors" />
                    </div>
                    <div className="rounded-2xl border border-paper-border bg-white/70 p-3">
                      <div className="mb-3 flex items-center justify-between gap-3">
                        <div>
                          <label className="block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">模型列表</label>
                          <div className="mt-1 text-xs text-sumi-dim">先自动拉取可用模型，再手动勾选需要使用的模型。</div>
                        </div>
                        <button type="button" onClick={() => loadModels(capability)} disabled={loadingModels[modelLoadingKey]} className="rounded-lg border border-paper-border bg-paper-base px-3 py-2 text-xs text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-sumi disabled:opacity-50">
                          {loadingModels[modelLoadingKey] ? '拉取中…' : '拉取模型'}
                        </button>
                      </div>
                      <ModelChecklist models={activePreset.availableModels} selectedModels={activePreset.selectedModels} onToggle={(modelId) => toggleModel(capability, modelId)} />
                    </div>
                    {selectedPreset.docsUrl ? <div className="flex justify-end"><a href={selectedPreset.docsUrl} target="_blank" rel="noopener" className="inline-flex items-center gap-1 text-xs text-aizuri hover:text-aizuri/80 transition-colors"><ExternalLink size={10} />文档</a></div> : null}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-paper-border/80 px-6 py-4">
          <button onClick={() => { if (!confirm('确定清空已保存的 API 设置吗？')) return; clearApiKeySettings(); setSettings(getApiKeySettings()); }} className="text-xs text-vermilion hover:text-vermilion-hover transition-colors">
            Clear All
          </button>
          <div className="flex gap-2">
            <button onClick={onClose} className="px-4 py-2 text-sm text-sumi-dim hover:text-sumi transition-colors">取消</button>
            <button onClick={handleSave} disabled={saving} className="rounded-md bg-vermilion px-4 py-2 text-sm font-medium text-white hover:bg-vermilion-hover transition-colors disabled:opacity-50">{saving ? '保存中…' : '保存'}</button>
          </div>
        </div>
      </div>
    </div>
  );
}
export default function App() {
  const isMobile = useIsMobile();
  const [authenticated, setAuthenticated] = useState(false);
  const [authCheck, setAuthCheck] = useState(false);
  const [view, setView] = useState<View>('home');
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginMessage, setLoginMessage] = useState('请先登录后再使用该功能');
  const [pendingView, setPendingView] = useState<View | null>(null);
  const [pendingCreateStory, setPendingCreateStory] = useState(false);
  const [workspaceCreateSignal, setWorkspaceCreateSignal] = useState(0);
  const [activeStoryId, setActiveStoryId] = useState<number | null>(null);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [currentChapter, setCurrentChapter] = useState<Chapter | null>(null);
  const [creatingChapter, setCreatingChapter] = useState(false);
  const [mobileTab, setMobileTab] = useState<MobileTab>('chat');

  useEffect(() => {
    setAuthenticated(isAuthenticated());
    setAuthCheck(true);
  }, []);

  useEffect(() => {
    const handleExpired = () => {
      setAuthenticated(false);
      setLoginMessage('登录已过期，请重新登录');
      setPendingView(view === 'square' ? null : view);
      setLoginOpen(true);
      clearWorkspaceState();
      if (view !== 'square') setView('square');
    };
    window.addEventListener('artverse:auth-expired', handleExpired);
    return () => window.removeEventListener('artverse:auth-expired', handleExpired);
  }, [view]);

  if (!authCheck) {
    return <div className="flex h-dvh w-screen items-center justify-center bg-paper-base"><div className="h-8 w-8 animate-spin rounded-full border-2 border-paper-border border-t-vermilion" /></div>;
  }

  const loadChapters = async (storyId: number) => {
    try {
      const chs = await listChapters(storyId);
      setChapters(chs);
      const savedIdx = Number(localStorage.getItem(LS_CHAPTER_IDX) || '0');
      const idx = chs.length > 0 ? Math.min(savedIdx, chs.length - 1) : 0;
      setCurrentIdx(idx);
      if (chs.length > 0) {
        const ch = await getChapter(chs[idx].id);
        setCurrentChapter(ch);
        localStorage.setItem(LS_CHAPTER_ID, String(chs[idx].id));
      }
    } catch {
      return;
    }
  };

  const loadEditor = async (storyId: number) => {
    setActiveStoryId(storyId);
    localStorage.setItem(LS_STORY_ID, String(storyId));
    setView('editor');
    await loadChapters(storyId);
  };

  const unloadEditor = () => {
    setActiveStoryId(null);
    setChapters([]);
    setCurrentChapter(null);
    clearWorkspaceState();
  };

  const refreshCurrentChapter = async () => {
    if (currentChapter) setCurrentChapter(await getChapter(currentChapter.id));
  };

  const handleChapterRefresh = async (chapterId: number) => {
    try {
      const ch = await getChapter(chapterId);
      setCurrentChapter(ch);
      if (activeStoryId) {
        const chs = await listChapters(activeStoryId);
        setChapters(chs);
      }
    } catch {
      return;
    }
  };

  const setChapterByIndex = async (idx: number) => {
    if (idx < 0 || idx >= chapters.length || !activeStoryId) return;
    setCurrentIdx(idx);
    const ch = await getChapter(chapters[idx].id);
    setCurrentChapter(ch);
    localStorage.setItem(LS_CHAPTER_ID, String(chapters[idx].id));
    localStorage.setItem(LS_CHAPTER_IDX, String(idx));
  };

  const handlePrevChapter = () => {
    if (currentIdx > 0) setChapterByIndex(currentIdx - 1);
  };

  const handleNextChapter = async () => {
    if (currentIdx < chapters.length - 1) {
      setChapterByIndex(currentIdx + 1);
      return;
    }
    if (activeStoryId) {
      setCreatingChapter(true);
      try {
        await createNextChapter(activeStoryId);
        const chs = await listChapters(activeStoryId);
        setChapters(chs);
        const idx = chs.length - 1;
        setCurrentIdx(idx);
        setCurrentChapter(await getChapter(chs[idx].id));
        localStorage.setItem(LS_CHAPTER_ID, String(chs[idx].id));
        localStorage.setItem(LS_CHAPTER_IDX, String(idx));
      } catch (e: any) {
        alert('操作失败：' + e.message);
      } finally {
        setCreatingChapter(false);
      }
    }
  };

  const handleDelete = async () => {
    if (!currentChapter || chapters.length <= 1 || !activeStoryId) return;
    if (!confirm('确定删除这一章吗？')) return;
    try {
      await deleteChapter(currentChapter.id);
      const chs = await listChapters(activeStoryId);
      setChapters(chs);
      const idx = Math.min(currentIdx, chs.length - 1);
      setCurrentIdx(idx);
      if (chs.length > 0) setCurrentChapter(await getChapter(chs[idx].id));
    } catch (e: any) {
      alert('操作失败：' + e.message);
    }
  };

  const requireLogin = (target?: View) => {
    setLoginMessage('请先登录后再使用该功能');
    setPendingView(target || null);
    setLoginOpen(true);
  };

  const goView = (target: View) => {
    if (target !== 'square' && !authenticated) {
      requireLogin(target);
      return;
    }
    if (view === 'editor') unloadEditor();
    setView(target);
  };

  const handleAuthSuccess = () => {
    setAuthenticated(true);
    setLoginOpen(false);
    clearWorkspaceState();
    if (pendingCreateStory) {
      setWorkspaceCreateSignal((prev) => prev + 1);
      setPendingCreateStory(false);
    }
    if (pendingView) {
      setView(pendingView);
      setPendingView(null);
    }
  };

  const openSettings = () => {
    if (!authenticated) {
      requireLogin();
      return;
    }
    setSettingsOpen(true);
  };

  const openWorkspaceCreateStory = () => {
    if (!authenticated) {
      setPendingCreateStory(true);
      requireLogin('workspace');
      return;
    }
    setPendingCreateStory(false);
    if (view === 'editor') unloadEditor();
    setWorkspaceCreateSignal((prev) => prev + 1);
    setView('workspace');
  };

  const navItem = (icon: React.ReactNode, label: string, target: View) => (
    <button
      onClick={() => goView(target)}
      className={
        'relative w-full rounded-md px-3 py-2.5 text-left text-sm font-medium transition-all duration-200 ' +
        (view === target
          ? 'text-vermilion bg-vermilion-light/30 border-l-[3px] border-l-vermilion pl-[9px]'
          : 'text-sumi-dim hover:bg-paper-base hover:text-sumi border-l-[3px] border-l-transparent pl-[9px]')
      }
    >
      <span className="flex items-center gap-3">{icon}{sidebarOpen && <span>{label}</span>}</span>
    </button>
  );

  return (
    <div className="flex h-dvh w-screen overflow-hidden bg-paper-base text-sumi">
      <aside className={'flex shrink-0 flex-col border-r border-paper-border bg-paper-surface transition-all duration-300 ' + (sidebarOpen ? 'w-[220px]' : 'w-14') + ' ' + (isMobile && view === 'editor' ? 'hidden' : '')}>
        <div className="flex h-14 items-center justify-between border-b border-paper-border px-3">
          {sidebarOpen && (
            <span className="font-display text-base font-bold tracking-wide text-vermilion flex items-center gap-1.5">
              <Sparkles size={15} />
              ArtVerse
            </span>
          )}
          <button onClick={() => setSidebarOpen(!sidebarOpen)} className='ml-auto text-sumi-faint hover:text-sumi-dim transition-colors' aria-label='Toggle sidebar'>
            {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
          </button>
        </div>
        {sidebarOpen && <div className="brush-divider mx-3 mt-0" />}
        <nav className="flex flex-1 flex-col gap-0.5 px-2 py-3">
          {navItem(<Sparkles size={18} />, '创作助手', 'home')}
          {navItem(<Globe size={18} />, '作品广场', 'square')}
          {navItem(<BookOpenText size={18} />, '故事工作区', 'workspace')}
          {navItem(<FileText size={18} />, '作品管理', 'myworks')}
          {navItem(<Paintbrush size={18} />, 'AI 生图', 'imagegen')}
        </nav>
        <div className="flex flex-col gap-0.5 border-t border-paper-border px-2 py-3">
          {authenticated ? (
            <>
              <button onClick={openSettings} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim hover:bg-paper-base hover:text-sumi transition-colors">
                <KeyRound size={18} />
                {sidebarOpen && <span>API 设置</span>}
              </button>
              <button onClick={() => { logoutUser(); setAuthenticated(false); unloadEditor(); clearWorkspaceState(); setView('home'); }} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim hover:bg-vermilion-light/30 hover:text-vermilion transition-colors">
                <LogOut size={18} />
                {sidebarOpen && <span>退出登录</span>}
              </button>
            </>
          ) : (
            <button onClick={() => requireLogin()} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim hover:bg-paper-base hover:text-sumi transition-colors">
              <LogIn size={18} />
              {sidebarOpen && <span>登录</span>}
            </button>
          )}
        </div>
      </aside>

      <div className="flex min-h-0 flex-1 flex-col">
        {view === 'home' && <MangaAgentPage onCreateStory={openWorkspaceCreateStory} />}
        {view === 'square' && <SquarePage />}
        {view === 'workspace' && <HomePage onSelectStory={(story) => loadEditor(story.id)} createStorySignal={workspaceCreateSignal} />}
        {view === 'imagegen' && <ImageGenPage />}
        {view === 'myworks' && <MyWorksPage />}

        {view === 'editor' && activeStoryId && (
          <div className="flex min-h-0 flex-1 flex-col">
            <div className="flex h-12 shrink-0 items-center justify-between border-b border-paper-border bg-paper-surface/90 px-3 backdrop-blur-md">
              <button onClick={() => { unloadEditor(); setView('workspace'); }} className="flex items-center gap-1.5 text-sm text-sumi-dim hover:text-vermilion transition-colors">
                <ChevronLeft size={16} />
                返回故事列表
              </button>
              <div className="flex items-center gap-2">
                {chapters.length > 0 && (
                  <select
                    value={currentIdx}
                    onChange={(e) => setChapterByIndex(Number(e.target.value))}
                    className="rounded-md border border-paper-border bg-paper-base px-2 py-1 text-xs text-sumi focus:border-vermilion focus:outline-none transition-colors"
                  >
                    {chapters.map((ch, i) => (
                      <option key={ch.id} value={i}>第 {ch.chapter_number} 章</option>
                    ))}
                  </select>
                )}
              </div>
            </div>

            {isMobile && chapters.length > 0 && (
              <div className="shrink-0 overflow-x-auto border-b border-paper-border bg-paper-surface px-2 py-2">
                <div className="flex gap-1">
                  {chapters.map((ch: Chapter, idx: number) => (
                    <button key={ch.id} onClick={() => setChapterByIndex(idx)} className={'shrink-0 rounded-full border px-3 py-1.5 text-xs transition-all duration-200 ' + (ch.id === currentChapter?.id ? 'border-vermilion bg-vermilion-light/50 text-vermilion font-medium' : 'border-paper-border bg-paper-base text-sumi-dim hover:text-sumi')}>
                      第 {ch.chapter_number} 章                    </button>
                  ))}
                </div>
              </div>
            )}

            {isMobile && (
              <div className="flex border-b border-paper-border bg-paper-surface">
                <button onClick={() => setMobileTab('chat')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-colors ' + (mobileTab === 'chat' ? 'border-b-2 border-vermilion text-vermilion' : 'text-sumi-dim hover:text-sumi')}>
                  <MessageSquare size={14} />
                  对话创作                </button>
                <button onClick={() => setMobileTab('manga')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-colors ' + (mobileTab === 'manga' ? 'border-b-2 border-vermilion text-vermilion' : 'text-sumi-dim hover:text-sumi')}>
                  <Image size={14} />
                  漫画分镜                </button>
              </div>
            )}

            {isMobile ? (
              <main className="min-h-0 flex-1">
                <div className={'h-full ' + (mobileTab === 'chat' ? '' : 'hidden')}>
                  <ChatPanel chapter={currentChapter} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
                <div className={'h-full ' + (mobileTab === 'manga' ? '' : 'hidden')}>
                  <MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
              </main>
            ) : (
              <main className="flex min-h-0 flex-1">
                <div className="w-1/2 border-r border-paper-border">
                  <ChatPanel chapter={currentChapter} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
                <div className="w-1/2">
                  <MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
              </main>
            )}

            <footer className="flex h-14 shrink-0 items-center justify-center gap-2 border-t border-paper-border bg-paper-surface/90 px-2 backdrop-blur-md md:gap-4">
              <button onClick={handlePrevChapter} disabled={currentIdx === 0} className="flex items-center gap-1 rounded-md border border-paper-border bg-paper-base px-3 py-2 text-sm font-medium text-sumi-dim disabled:cursor-not-allowed disabled:opacity-30 hover:border-sumi-faint hover:text-sumi transition-colors">
                <ChevronLeft size={16} />
                {!isMobile && '上一章'}
              </button>
              <button onClick={handleDelete} disabled={!currentChapter || chapters.length <= 1} className='flex items-center gap-1.5 rounded-md border border-vermilion/20 bg-vermilion-light/20 px-3 py-2 text-sm font-medium text-vermilion disabled:cursor-not-allowed disabled:opacity-30 hover:bg-vermilion-light/40 transition-colors' aria-label='Delete chapter'>
                <Trash2 size={14} />
              </button>
              <div className="flex items-center gap-1">
                {chapters.map((ch: Chapter, i: number) => (
                  <button key={ch.id} onClick={() => setChapterByIndex(i)} className={'h-2 w-2 rounded-full transition-all duration-200 ' + (i === currentIdx ? 'bg-vermilion scale-125' : 'bg-sumi-faint/30 hover:bg-sumi-faint/60')} aria-label={'切换到第 ' + ch.chapter_number + ' 章'} />
                ))}
              </div>
              <button onClick={handleNextChapter} disabled={creatingChapter} className="flex items-center gap-1 rounded-md bg-vermilion px-3 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40 hover:bg-vermilion-hover transition-colors md:px-5">
                {currentIdx === chapters.length - 1 ? (<><Plus size={16} />{creatingChapter ? '创建中…' : isMobile ? '新建' : '下一章（新建）'}</>) : (<><span>{!isMobile && '下一章'}</span><ChevronRight size={16} /></>)}
              </button>
            </footer>
          </div>
        )}
      </div>

      <ApiKeySettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} />

      {loginOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-sumi/30 backdrop-blur-sm" onClick={() => setLoginOpen(false)}>
          <div className="w-full max-w-sm animate-fade-in" onClick={(e) => e.stopPropagation()}>
            <LoginPage variant="modal" message={loginMessage} onCancel={() => setLoginOpen(false)} onAuthSuccess={handleAuthSuccess} />
          </div>
        </div>
      )}
    </div>
  );
}

