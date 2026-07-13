export interface ProviderMeta {
  label: string;
  emoji: string;
  color: string;
}

export interface GroupedModels {
  provider: ProviderMeta;
  models: string[];
}

const PROVIDER_PATTERNS: Array<{
  test: (id: string) => boolean;
  label: string;
  emoji: string;
  color: string;
}> = [
  { test: (id) => id.startsWith('deepseek'), label: 'DeepSeek', emoji: 'D', color: 'bg-blue-100 text-blue-700' },
  { test: (id) => /^gpt-|^o[134]-|^chatgpt/.test(id), label: 'OpenAI', emoji: 'O', color: 'bg-emerald-100 text-emerald-700' },
  { test: (id) => id.startsWith('claude'), label: 'Claude', emoji: 'C', color: 'bg-amber-100 text-amber-700' },
  { test: (id) => id.startsWith('gemini'), label: 'Gemini', emoji: 'G', color: 'bg-sky-100 text-sky-700' },
  { test: (id) => id.startsWith('doubao'), label: 'Doubao', emoji: 'B', color: 'bg-teal-100 text-teal-700' },
  { test: (id) => id.startsWith('qwen'), label: 'Qwen', emoji: 'Q', color: 'bg-purple-100 text-purple-700' },
  { test: (id) => id.startsWith('grok'), label: 'Grok', emoji: 'X', color: 'bg-indigo-100 text-indigo-700' },
  { test: (id) => id.startsWith('glm'), label: 'GLM', emoji: 'Z', color: 'bg-cyan-100 text-cyan-700' },
  { test: (id) => id.startsWith('kimi'), label: 'Kimi', emoji: 'K', color: 'bg-violet-100 text-violet-700' },
  { test: (id) => /minimax|abab/.test(id), label: 'MiniMax', emoji: 'M', color: 'bg-fuchsia-100 text-fuchsia-700' },
  { test: (id) => /seedance|jimeng/.test(id), label: 'Jimeng', emoji: 'J', color: 'bg-rose-100 text-rose-700' },
  { test: (id) => /flux|stable|schnell/.test(id), label: 'Stability', emoji: 'S', color: 'bg-lime-100 text-lime-700' },
  { test: (id) => /black-forest|midjourney/.test(id), label: 'Image', emoji: 'I', color: 'bg-pink-100 text-pink-700' },
  { test: (id) => id.includes('/'), label: 'OpenRouter', emoji: 'R', color: 'bg-orange-100 text-orange-700' },
];

export function detectProvider(modelId: string): ProviderMeta {
  const lower = modelId.toLowerCase();
  for (const pattern of PROVIDER_PATTERNS) {
    if (pattern.test(lower)) {
      return { label: pattern.label, emoji: pattern.emoji, color: pattern.color };
    }
  }
  return { label: 'Custom', emoji: 'U', color: 'bg-bg-raised text-text-secondary' };
}

export function groupModelsByProvider(models: string[]): GroupedModels[] {
  const groups: Record<string, GroupedModels> = {};
  for (const model of models) {
    const provider = detectProvider(model);
    if (!groups[provider.label]) {
      groups[provider.label] = { provider, models: [] };
    }
    groups[provider.label].models.push(model);
  }
  return Object.values(groups).sort((a, b) => a.provider.label.localeCompare(b.provider.label));
}
