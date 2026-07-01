import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { ArrowLeft, Bot, ExternalLink, Image, KeyRound, Pencil, Plus, Save, Trash2, Workflow } from 'lucide-react';
import {
  clearApiKeySettings,
  discoverProviderModels,
  getApiKeySettings,
  getProviderModelSelections,
  getUserProviderConfigs,
  mergeServerProviderConfigs,
  saveApiKeySettings,
  saveUserProviderConfig,
  toProviderEndpointConfig,
  type ApiCapability,
  type ApiKeySettings,
  type CapabilityProviderSettings,
  type ProviderPresetConfig,
} from '../api';

type ProviderPreset = {
  id: string;
  label: string;
  docsUrl?: string;
  baseUrl: string;
  models: string[];
};

type EditorState = {
  mode: 'list' | 'edit';
  entryId?: string;
  isNew?: boolean;
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

const CAPABILITY_CARDS: Array<{ capability: ApiCapability; title: string; description: string; icon: ReactNode }> = [
  { capability: 'llm', title: '对话 / 小说', description: '统一管理文本模型 API。', icon: <Bot size={16} className="text-vermilion" /> },
  { capability: 'image', title: '生图模型', description: '统一管理图像生成 API。', icon: <Image size={16} className="text-aizuri" /> },
  { capability: 'workflow', title: '工作流 / Agent', description: '统一管理 Coze、Dify 或自定义工作流。', icon: <Workflow size={16} className="text-kinpaku" /> },
];

function createEntryId(capability: ApiCapability): string {
  return `${capability}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function getPresetTemplate(capability: ApiCapability, presetId: string): ProviderPreset {
  return PROVIDER_PRESETS[capability].find((item) => item.id === presetId) || PROVIDER_PRESETS[capability][0];
}

function createEntryFromPreset(capability: ApiCapability, presetId: string): ProviderPresetConfig {
  const preset = getPresetTemplate(capability, presetId);
  return {
    presetId: preset.id,
    label: preset.label,
    mode: preset.id === 'custom' ? 'custom' : 'official',
    apiKey: '',
    baseUrl: preset.baseUrl,
    selectedModels: [],
    availableModels: [],
  };
}

function getEntry(settings: ApiKeySettings, capability: ApiCapability, entryId?: string): ProviderPresetConfig | null {
  if (!entryId) return null;
  return settings.providers[capability].entries[entryId] || null;
}

function updateCapability(
  settings: ApiKeySettings,
  capability: ApiCapability,
  updater: (current: CapabilityProviderSettings) => CapabilityProviderSettings,
): ApiKeySettings {
  return {
    ...settings,
    providers: {
      ...settings.providers,
      [capability]: updater(settings.providers[capability]),
    },
  };
}

function updateEntry(
  settings: ApiKeySettings,
  capability: ApiCapability,
  entryId: string,
  patch: Partial<ProviderPresetConfig>,
): ApiKeySettings {
  return updateCapability(settings, capability, (current) => {
    const existing = current.entries[entryId];
    if (!existing) return current;
    return {
      ...current,
      entries: {
        ...current.entries,
        [entryId]: {
          ...existing,
          ...patch,
        },
      },
    };
  });
}

function sortEntries(provider: CapabilityProviderSettings): Array<[string, ProviderPresetConfig]> {
  return Object.entries(provider.entries).sort(([leftId], [rightId]) => leftId.localeCompare(rightId));
}

function isConfiguredEntry(capability: ApiCapability, entry: ProviderPresetConfig): boolean {
  const template = getPresetTemplate(capability, entry.presetId);
  const selectedModels = [...entry.selectedModels].sort().join('\n');
  const templateModels = [...template.models].sort().join('\n');
  const availableModels = [...entry.availableModels].sort().join('\n');
  const templateAvailable = [...template.models].sort().join('\n');

  return Boolean(
    entry.apiKey.trim()
    || (entry.label.trim() && entry.label.trim() !== template.label)
    || (entry.baseUrl.trim() && entry.baseUrl.trim() !== template.baseUrl)
    || entry.mode !== (entry.presetId === 'custom' ? 'custom' : 'official')
    || selectedModels !== templateModels
    || availableModels !== templateAvailable,
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
    return <div className="rounded-xl border border-dashed border-paper-border bg-white/70 px-3 py-4 text-sm text-sumi-faint">还没有可选模型，先填写 Base URL 和 Key 后再拉取模型。</div>;
  }

  return (
    <div className="max-h-60 overflow-y-auto rounded-xl border border-paper-border bg-white/80 p-2.5">
      <div className="grid gap-2">
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
    </div>
  );
}

function ApiEntryCard({
  capability,
  entry,
  onEdit,
  onDelete,
}: {
  capability: ApiCapability;
  entry: ProviderPresetConfig;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const preset = getPresetTemplate(capability, entry.presetId);
  return (
    <div className="rounded-2xl border border-paper-border bg-white/80 p-3 transition-colors">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1 text-left">
          <div className="truncate text-sm font-medium text-sumi">{entry.label || '未命名 API'}</div>
          <div className="mt-1 truncate text-xs text-sumi-dim">{preset.label}</div>
          <div className="mt-1 truncate text-[11px] text-sumi-faint">{entry.baseUrl || '未填写 Base URL'}</div>
        </div>
        <div className="flex items-center gap-1">
          <button type="button" onClick={onEdit} className="rounded-lg border border-paper-border bg-paper-base p-2 text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion" aria-label="编辑 API">
            <Pencil size={14} />
          </button>
          <button type="button" onClick={onDelete} className="rounded-lg border border-paper-border bg-paper-base p-2 text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion" aria-label="删除 API">
            <Trash2 size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}

function EmptyApiState({
  onCreate,
}: {
  onCreate: () => void;
}) {
  return (
    <div className="flex min-h-[260px] flex-1 flex-col items-center justify-center rounded-2xl border border-dashed border-paper-border bg-white/55 px-6 py-8 text-center">
      <div className="text-sm font-medium text-sumi">还没有添加 API</div>
      <p className="mt-2 max-w-[240px] text-xs leading-6 text-sumi-dim">
        先添加一条新的配置，这一块后面就会在这里显示你已经保存的 API 列表。
      </p>
      <button
        type="button"
        onClick={onCreate}
        className="mt-4 inline-flex items-center gap-1 rounded-xl border border-paper-border bg-paper-base px-3 py-2 text-xs text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion"
      >
        <Plus size={14} />
        添加新 API
      </button>
    </div>
  );
}

export default function ApiSettingsPage() {
  const [settings, setSettings] = useState<ApiKeySettings>(() => getApiKeySettings());
  const [saving, setSaving] = useState<Record<ApiCapability, boolean>>({ llm: false, image: false, workflow: false });
  const [loadingRemote, setLoadingRemote] = useState(false);
  const [error, setError] = useState('');
  const [loadingModels, setLoadingModels] = useState<Record<string, boolean>>({});
  const [editors, setEditors] = useState<Record<ApiCapability, EditorState>>({
    llm: { mode: 'list' },
    image: { mode: 'list' },
    workflow: { mode: 'list' },
  });

  useEffect(() => {
    const local = getApiKeySettings();
    setSettings(local);
    setError('');
    setLoadingRemote(true);
    getUserProviderConfigs()
      .then((remote) => {
        const merged = mergeServerProviderConfigs(local, remote);
        setSettings(merged);
        saveApiKeySettings(merged);
      })
      .catch(() => undefined)
      .finally(() => setLoadingRemote(false));
  }, []);

  const currentEntries = useMemo(() => ({
    llm: sortEntries(settings.providers.llm),
    image: sortEntries(settings.providers.image),
    workflow: sortEntries(settings.providers.workflow),
  }), [settings]);

  const visibleEntries = useMemo(() => ({
    llm: currentEntries.llm.filter(([, entry]) => isConfiguredEntry('llm', entry)),
    image: currentEntries.image.filter(([, entry]) => isConfiguredEntry('image', entry)),
    workflow: currentEntries.workflow.filter(([, entry]) => isConfiguredEntry('workflow', entry)),
  }), [currentEntries]);

  const selectedModelCounts = useMemo(() => ({
    llm: getProviderModelSelections('llm').length,
    image: getProviderModelSelections('image').length,
    workflow: getProviderModelSelections('workflow').length,
  }), [settings]);

  const openNewEditor = (capability: ApiCapability) => {
    const entryId = createEntryId(capability);
    setSettings((prev) => updateCapability(prev, capability, (current) => ({
      ...current,
      entries: {
        ...current.entries,
        [entryId]: createEntryFromPreset(capability, PROVIDER_PRESETS[capability][0].id),
      },
    })));
    setEditors((prev) => ({
      ...prev,
      [capability]: { mode: 'edit', entryId, isNew: true },
    }));
  };

  const openEditor = (capability: ApiCapability, entryId: string, isNew = false) => {
    setEditors((prev) => ({
      ...prev,
      [capability]: { mode: 'edit', entryId, isNew },
    }));
  };

  const closeEditor = (capability: ApiCapability) => {
    const editor = editors[capability];
    if (editor.mode === 'edit' && editor.isNew && editor.entryId) {
      setSettings((prev) => updateCapability(prev, capability, (current) => {
        const nextEntries = { ...current.entries };
        delete nextEntries[editor.entryId];
        return {
          ...current,
          entries: nextEntries,
        };
      }));
    }
    setEditors((prev) => ({
      ...prev,
      [capability]: { mode: 'list' },
    }));
  };

  const handleDelete = (capability: ApiCapability, entryId: string) => {
    if (!confirm('确定删除这个 API 配置吗？')) return;
    setSettings((prev) => updateCapability(prev, capability, (current) => {
      const nextEntries = { ...current.entries };
      delete nextEntries[entryId];
      const remainingIds = Object.keys(nextEntries);
      if (remainingIds.length === 0) {
        const defaultEntryId = createEntryId(capability);
        nextEntries[defaultEntryId] = createEntryFromPreset(capability, PROVIDER_PRESETS[capability][0].id);
        return {
          activePresetId: defaultEntryId,
          entries: nextEntries,
        };
      }
      return {
        activePresetId: current.activePresetId === entryId ? remainingIds[0] : current.activePresetId,
        entries: nextEntries,
      };
    }));
    if (editors[capability].entryId === entryId) {
      setEditors((prev) => ({ ...prev, [capability]: { mode: 'list' } }));
    }
  };

  const handlePresetChange = (capability: ApiCapability, entryId: string, presetId: string) => {
    const template = getPresetTemplate(capability, presetId);
    setSettings((prev) => {
      const currentEntry = getEntry(prev, capability, entryId);
      if (!currentEntry) return prev;
      const nextLabel = currentEntry.label === getPresetTemplate(capability, currentEntry.presetId).label
        ? template.label
        : currentEntry.label;
      return updateEntry(prev, capability, entryId, {
        presetId: template.id,
        label: nextLabel,
        mode: template.id === 'custom' ? 'custom' : 'official',
        baseUrl: template.baseUrl,
        availableModels: Array.from(new Set([...currentEntry.availableModels, ...currentEntry.selectedModels])),
        selectedModels: currentEntry.selectedModels,
      });
    });
  };

  const toggleModel = (capability: ApiCapability, entryId: string, modelId: string) => {
    setSettings((prev) => {
      const entry = getEntry(prev, capability, entryId);
      if (!entry) return prev;
      const selectedModels = entry.selectedModels.includes(modelId)
        ? entry.selectedModels.filter((item) => item !== modelId)
        : [...entry.selectedModels, modelId];
      return updateEntry(prev, capability, entryId, { selectedModels });
    });
  };

  const loadModels = async (capability: ApiCapability, entryId: string) => {
    const entry = getEntry(settings, capability, entryId);
    if (!entry) return;
    const loadingKey = `${capability}:${entryId}`;
    setLoadingModels((prev) => ({ ...prev, [loadingKey]: true }));
    setError('');
    try {
      const discovered = await discoverProviderModels(capability, toProviderEndpointConfig(entry));
      setSettings((prev) => updateEntry(prev, capability, entryId, {
        availableModels: Array.from(new Set([...discovered, ...entry.availableModels, ...entry.selectedModels])),
        selectedModels: entry.selectedModels.length > 0 ? entry.selectedModels : discovered.slice(0, 1),
      }));
    } catch (e: any) {
      setError(e?.message || '拉取模型失败');
    } finally {
      setLoadingModels((prev) => ({ ...prev, [loadingKey]: false }));
    }
  };

  const saveCapabilityEntry = async (capability: ApiCapability, entryId: string) => {
    const entry = getEntry(settings, capability, entryId);
    if (!entry) return;
    setSaving((prev) => ({ ...prev, [capability]: true }));
    setError('');
    try {
      saveApiKeySettings(settings);
      await saveUserProviderConfig(capability, toProviderEndpointConfig(entry));
      setEditors((prev) => ({
        ...prev,
        [capability]: { mode: 'list' },
      }));
    } catch (e: any) {
      setError(e?.message || '保存失败');
    } finally {
      setSaving((prev) => ({ ...prev, [capability]: false }));
    }
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
      <header className="border-b border-paper-border bg-paper-surface/80 px-5 py-4 backdrop-blur-sm">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-vermilion/15 bg-vermilion-light/20">
            <KeyRound size={18} className="text-kinpaku" />
          </div>
          <div>
            <h1 className="font-display text-lg font-semibold text-sumi">API 设置</h1>
            <p className="text-xs text-sumi-dim">每一块都可以维护多条 API 供应商配置；对话页会汇总这些配置里勾选过的模型。</p>
          </div>
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
        <div className="flex min-h-full flex-col">
          {loadingRemote ? <div className="rounded-xl border border-paper-border bg-paper-surface px-4 py-3 text-sm text-sumi-dim">正在读取后端已保存的 API 配置...</div> : null}
          {error ? <div className="mt-4 rounded-xl border border-vermilion/20 bg-vermilion-light/20 px-4 py-3 text-sm text-vermilion">{error}</div> : null}

          <div className="mt-5 grid flex-1 auto-rows-fr gap-4 xl:grid-cols-3">
            {CAPABILITY_CARDS.map(({ capability, title, description, icon }) => {
              const editor = editors[capability];
              const editingEntry = editor.mode === 'edit' ? getEntry(settings, capability, editor.entryId) : null;
              const supportsModelSelection = capability !== 'workflow';

              return (
                <div key={capability} className="flex h-full min-h-[370px] flex-col rounded-2xl border border-paper-border bg-paper-surface p-4 shadow-sm">
                  <div className="mb-4 flex items-start justify-between gap-3">
                    <div>
                      <div className="flex items-center gap-2 text-sm font-medium text-sumi">{icon}{title}</div>
                      <p className="mt-1 text-xs leading-5 text-sumi-dim">{description}</p>
                    </div>
                    {editor.mode === 'list' ? (
                      <button type="button" onClick={() => openNewEditor(capability)} className="inline-flex items-center gap-1 rounded-xl border border-paper-border bg-white/80 px-3 py-2 text-xs text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion">
                        <Plus size={14} />
                        添加新 API
                      </button>
                    ) : null}
                  </div>

                  {editor.mode === 'list' ? (
                    <div className="flex flex-1 flex-col">
                      {visibleEntries[capability].length > 0 ? (
                        <div className="space-y-3">
                          <div className="rounded-xl border border-paper-border/80 bg-white/60 px-3 py-2 text-xs leading-6 text-sumi-dim">
                            已添加 {visibleEntries[capability].length} 个 API 供应商
                            {supportsModelSelection ? `，当前会向对话页汇总 ${selectedModelCounts[capability]} 个已勾选模型。` : '。'}
                          </div>
                          {visibleEntries[capability].map(([entryId, entry]) => (
                            <ApiEntryCard
                              key={entryId}
                              capability={capability}
                              entry={entry}
                              onEdit={() => openEditor(capability, entryId)}
                              onDelete={() => handleDelete(capability, entryId)}
                            />
                          ))}
                        </div>
                      ) : (
                        <EmptyApiState onCreate={() => openNewEditor(capability)} />
                      )}
                    </div>
                  ) : editingEntry ? (
                    <div className="space-y-4">
                      <div className="flex items-center justify-between gap-3">
                        <button type="button" onClick={() => closeEditor(capability)} className="inline-flex items-center gap-1 text-xs text-sumi-dim transition-colors hover:text-sumi">
                          <ArrowLeft size={14} />
                          返回列表
                        </button>
                        <button type="button" onClick={() => saveCapabilityEntry(capability, editor.entryId!)} disabled={saving[capability]} className="inline-flex items-center gap-1 rounded-xl bg-vermilion px-3 py-2 text-xs font-medium text-white transition-colors hover:bg-vermilion-hover disabled:opacity-50">
                          <Save size={14} />
                          {saving[capability] ? '保存中...' : '保存'}
                        </button>
                      </div>

                      <div>
                        <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">重命名此 API</label>
                        <input
                          type="text"
                          value={editingEntry.label}
                          onChange={(e) => setSettings((prev) => updateEntry(prev, capability, editor.entryId!, { label: e.target.value }))}
                          placeholder="例如：主力 OpenAI / 备用 DeepSeek"
                          className="w-full rounded-xl border border-paper-border bg-white/85 px-3 py-2 text-sm text-sumi placeholder-sumi-faint transition-colors focus:border-vermilion focus:outline-none"
                        />
                      </div>

                      <div>
                        <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">服务类型</label>
                        <select
                          value={editingEntry.presetId}
                          onChange={(e) => handlePresetChange(capability, editor.entryId!, e.target.value)}
                          className="w-full rounded-xl border border-paper-border bg-white/85 px-3 py-2 text-sm text-sumi transition-colors focus:border-vermilion focus:outline-none"
                        >
                          {PROVIDER_PRESETS[capability].map((preset) => (
                            <option key={preset.id} value={preset.id}>{preset.label}</option>
                          ))}
                        </select>
                      </div>

                      <div>
                        <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">接入方式</label>
                        <div className="grid grid-cols-2 gap-2">
                          {(['official', 'custom'] as const).map((mode) => (
                            <button
                              key={mode}
                              type="button"
                              onClick={() => setSettings((prev) => updateEntry(prev, capability, editor.entryId!, { mode }))}
                              className={'rounded-xl border px-3 py-2 text-sm transition-colors ' + (editingEntry.mode === mode ? 'border-vermilion bg-vermilion-light/20 text-vermilion' : 'border-paper-border bg-white/80 text-sumi-dim hover:text-sumi')}
                            >
                              {mode === 'official' ? '官方 API' : '自定义 API'}
                            </button>
                          ))}
                        </div>
                      </div>

                      <div>
                        <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">API Key</label>
                        <input
                          type="password"
                          value={editingEntry.apiKey}
                          onChange={(e) => setSettings((prev) => updateEntry(prev, capability, editor.entryId!, { apiKey: e.target.value }))}
                          placeholder="sk-... / pat-..."
                          className="w-full rounded-xl border border-paper-border bg-white/85 px-3 py-2 text-sm text-sumi placeholder-sumi-faint transition-colors focus:border-vermilion focus:outline-none"
                        />
                      </div>

                      <div>
                        <label className="mb-1 block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">Base URL</label>
                        <input
                          type="text"
                          value={editingEntry.baseUrl}
                          onChange={(e) => setSettings((prev) => updateEntry(prev, capability, editor.entryId!, { baseUrl: e.target.value }))}
                          placeholder="https://api.example.com/v1"
                          className="w-full rounded-xl border border-paper-border bg-white/85 px-3 py-2 text-sm text-sumi placeholder-sumi-faint transition-colors focus:border-vermilion focus:outline-none"
                        />
                      </div>

                      {supportsModelSelection ? (
                        <div className="rounded-2xl border border-paper-border bg-white/70 p-3">
                          <div className="mb-3 flex items-center justify-between gap-3">
                            <div>
                              <label className="block text-[11px] font-medium uppercase tracking-[0.18em] text-sumi-faint">模型列表</label>
                              <div className="mt-1 text-xs text-sumi-dim">先拉取可用模型，再勾选这条 API 要使用的模型。</div>
                            </div>
                            <button
                              type="button"
                              onClick={() => loadModels(capability, editor.entryId!)}
                              disabled={loadingModels[`${capability}:${editor.entryId}`]}
                              className="rounded-lg border border-paper-border bg-paper-base px-3 py-2 text-xs text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-sumi disabled:opacity-50"
                            >
                              {loadingModels[`${capability}:${editor.entryId}`] ? '拉取中...' : '拉取模型'}
                            </button>
                          </div>
                          <ModelChecklist
                            models={editingEntry.availableModels}
                            selectedModels={editingEntry.selectedModels}
                            onToggle={(modelId) => toggleModel(capability, editor.entryId!, modelId)}
                          />
                        </div>
                      ) : (
                        <div className="rounded-2xl border border-paper-border bg-white/70 px-3 py-3 text-xs leading-6 text-sumi-dim">
                          工作流类配置只保存 API Key 和 Base URL。具体 workflow / agent 由对应服务端配置决定，这里不展示模型列表。
                        </div>
                      )}

                      {getPresetTemplate(capability, editingEntry.presetId).docsUrl ? (
                        <div className="flex justify-end">
                          <a
                            href={getPresetTemplate(capability, editingEntry.presetId).docsUrl}
                            target="_blank"
                            rel="noopener"
                            className="inline-flex items-center gap-1 text-xs text-aizuri transition-colors hover:text-aizuri/80"
                          >
                            <ExternalLink size={10} />
                            文档
                          </a>
                        </div>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>
      </div>

      <div className="flex items-center justify-between border-t border-paper-border/80 px-5 py-4">
        <button
          onClick={() => {
            if (!confirm('确定清空已保存的 API 设置吗？')) return;
            clearApiKeySettings();
            setSettings(getApiKeySettings());
            setEditors({ llm: { mode: 'list' }, image: { mode: 'list' }, workflow: { mode: 'list' } });
          }}
          className="text-xs text-vermilion transition-colors hover:text-vermilion-hover"
        >
          Clear All
        </button>
        <div className="text-xs text-sumi-faint">这里保存的是多个 API 供应商配置；对话页会汇总这些配置里勾选过的模型。</div>
      </div>
    </div>
  );
}
