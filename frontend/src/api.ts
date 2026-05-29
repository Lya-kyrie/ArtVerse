const BASE = '';
export const DEEPSEEK_USAGE_URL = 'https://platform.deepseek.com/usage';
export const IMAGE2_CONSOLE_URL = 'https://api.duojie.games/console/token';

// ─── Auth (JWT) ──────────────────────────────────────────────

const LS_ACCESS_TOKEN = 'artverse.accessToken';
const LS_REFRESH_TOKEN = 'artverse.refreshToken';
const LS_USER = 'artverse.user';

export interface UserInfo {
  id: number;
  username: string;
  email: string;
}

function getAccessToken(): string | null {
  return localStorage.getItem(LS_ACCESS_TOKEN);
}

function getRefreshToken(): string | null {
  return localStorage.getItem(LS_REFRESH_TOKEN);
}

export function getUser(): UserInfo | null {
  const raw = localStorage.getItem(LS_USER);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

export function isAuthenticated(): boolean {
  return !!getAccessToken();
}

function saveAuth(data: { access_token: string; refresh_token: string; user: UserInfo }): void {
  localStorage.setItem(LS_ACCESS_TOKEN, data.access_token);
  localStorage.setItem(LS_REFRESH_TOKEN, data.refresh_token);
  localStorage.setItem(LS_USER, JSON.stringify(data.user));
}

export function clearAuth(): void {
  localStorage.removeItem(LS_ACCESS_TOKEN);
  localStorage.removeItem(LS_REFRESH_TOKEN);
  localStorage.removeItem(LS_USER);
}

let refreshPromise: Promise<boolean> | null = null;

async function tryRefreshToken(): Promise<boolean> {
  const rt = getRefreshToken();
  if (!rt) return false;

  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const res = await fetch(`${BASE}/api/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: rt }),
        });
        if (!res.ok) return false;
        const data = await res.json();
        localStorage.setItem(LS_ACCESS_TOKEN, data.access_token);
        return true;
      } catch {
        return false;
      }
    })();
  }
  const ok = await refreshPromise;
  refreshPromise = null;
  return ok;
}

export async function loginUser(username: string, password: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  saveAuth(await res.json());
}

export async function registerUser(username: string, email: string, password: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, email, password }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  saveAuth(await res.json());
}

export async function logoutUser(): Promise<void> {
  const token = getAccessToken();
  if (token) {
    try {
      await fetch(`${BASE}/api/auth/logout`, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
      });
    } catch { /* ignore */ }
  }
  clearAuth();
}

// ─── API key settings ────────────────────────────────────────

const LS_DEEPSEEK_API_KEY = 'lorevista.deepseekApiKey';
const LS_IMAGE_API_KEY = 'lorevista.imageApiKey';
export const API_KEY_CHANGE_EVENT = 'lorevista:api-key-change';

export interface ApiKeySettings {
  deepseekApiKey: string;
  imageApiKey: string;
}

export function getApiKeySettings(): ApiKeySettings {
  return {
    deepseekApiKey: localStorage.getItem(LS_DEEPSEEK_API_KEY) || '',
    imageApiKey: localStorage.getItem(LS_IMAGE_API_KEY) || '',
  };
}

export function saveApiKeySettings(settings: ApiKeySettings): void {
  const deepseek = settings.deepseekApiKey.trim();
  const image = settings.imageApiKey.trim();
  if (deepseek) localStorage.setItem(LS_DEEPSEEK_API_KEY, deepseek);
  else localStorage.removeItem(LS_DEEPSEEK_API_KEY);
  if (image) localStorage.setItem(LS_IMAGE_API_KEY, image);
  else localStorage.removeItem(LS_IMAGE_API_KEY);
  try { window.dispatchEvent(new Event(API_KEY_CHANGE_EVENT)); } catch { /* ignore */ }
}

export function clearApiKeySettings(): void {
  saveApiKeySettings({ deepseekApiKey: '', imageApiKey: '' });
}

export async function getUserApiKeys(): Promise<{ provider: string; api_key_masked: string }[]> {
  const res = await authFetch(`${BASE}/api/user/api-keys`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function saveUserApiKey(provider: string, apiKey: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/user/api-keys`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, api_key: apiKey }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

// ─── Auth-aware fetch ────────────────────────────────────────

function apiHeaders(json = false): HeadersInit {
  const token = getAccessToken();
  return {
    ...(json ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
  };
}

async function authFetch(input: RequestInfo, init?: RequestInit): Promise<Response> {
  let res = await fetch(input, { ...init, headers: { ...apiHeaders(), ...(init?.headers || {}) } });
  if (res.status === 401) {
    const refreshed = await tryRefreshToken();
    if (refreshed) {
      res = await fetch(input, { ...init, headers: { ...apiHeaders(), ...(init?.headers || {}) } });
    } else {
      clearAuth();
      window.dispatchEvent(new CustomEvent('artverse:auth-expired'));
    }
  }
  return res;
}

export interface Story {
  id: number;
  title: string;
  description?: string;
  cover_image?: string | null;
  has_character_profiles?: boolean;
  has_ref_image?: boolean;
  created_at: string;
}

export interface ChatMessage {
  id: number;
  chapter_id: number;
  role: string;
  content: string;
  created_at: string;
}

export interface MangaImage {
  id: number;
  chapter_id: number;
  image_number: number;
  image_path: string;
  prompt: string | null;
  created_at: string;
}

export interface Chapter {
  id: number;
  story_id: number;
  chapter_number: number;
  novel_content: string | null;
  content_source?: 'chat' | 'import' | null;
  created_at: string;
  messages: ChatMessage[];
  images: MangaImage[];
}

// ─── Story ──────────────────────────────────────────────────

export async function createStory(title: string = '未命名故事', description: string = ''): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, description }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function listStories(): Promise<Story[]> {
  const res = await authFetch(`${BASE}/api/stories`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function updateStory(storyId: number, data: { title?: string; description?: string }): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStory(storyId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}

export async function exportStory(story: Story): Promise<void> {
  const res = await fetch(`${BASE}/api/stories/${story.id}/export`, { headers: apiHeaders() });
  if (!res.ok) throw new Error(await res.text());
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const safeTitle = (story.title || `story-${story.id}`).replace(/[\\/:*?"<>|]+/g, '_');
  const a = document.createElement('a');
  a.href = url;
  a.download = `${safeTitle}_lorevista.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export interface ImportStoryProgress {
  phase: 'uploading' | 'processing';
  percent?: number;
  message: string;
}

function parseApiError(text: string): string {
  try {
    const data = JSON.parse(text);
    if (typeof data?.detail === 'string') return data.detail;
    if (Array.isArray(data?.detail)) return data.detail.map((item: any) => item?.msg || JSON.stringify(item)).join('; ');
    if (typeof data?.error === 'string') return data.error;
  } catch {
    // Plain text response.
  }
  return text || 'Request failed';
}

export function importStoryPackage(
  file: File,
  onProgress?: (progress: ImportStoryProgress) => void,
): Promise<Story> {
  return new Promise((resolve, reject) => {
    const doSend = () => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/api/stories/import`);
      const headers = apiHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        if (typeof value === 'string') xhr.setRequestHeader(key, value);
      });
      xhr.responseType = 'text';

      const formData = new FormData();
      formData.append('file', file);

      xhr.upload.onprogress = (event) => {
        if (!event.lengthComputable) {
          onProgress?.({ phase: 'uploading', message: '正在上传作品包...' });
          return;
        }
        const percent = Math.max(1, Math.min(99, Math.round((event.loaded / event.total) * 100)));
        onProgress?.({ phase: 'uploading', percent, message: `正在上传作品包 ${percent}%` });
      };

      xhr.onload = async () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.({ phase: 'processing', percent: 100, message: '导入完成，正在刷新作品列表...' });
          try {
            resolve(JSON.parse(xhr.responseText));
          } catch {
            reject(new Error('导入完成但响应格式无效'));
          }
          return;
        }
        if (xhr.status === 401) {
          const refreshed = await tryRefreshToken();
          if (refreshed) {
            doSend();
            return;
          }
          clearAuth();
          window.dispatchEvent(new CustomEvent('artverse:auth-expired'));
          reject(new Error('登录已过期，请重新登录'));
          return;
        }
        reject(new Error(parseApiError(xhr.responseText)));
      };

      xhr.onerror = () => reject(new Error('网络连接中断。请检查服务器端口、防火墙或上传包大小限制。'));
      xhr.onabort = () => reject(new Error('导入已取消'));
      xhr.ontimeout = () => reject(new Error('导入超时。作品包较大时请稍后重试。'));

      onProgress?.({ phase: 'uploading', percent: 0, message: '准备上传作品包...' });
      xhr.send(formData);
      xhr.upload.onload = () => {
        onProgress?.({ phase: 'processing', percent: 100, message: '上传完成，服务器正在解压并写入作品...' });
      };
    };
    doSend();
  });
}

export async function uploadStoryCover(storyId: number, base64: string): Promise<string> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/upload-cover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cover_image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.cover_image;
}

function mangaStaticPath(imagePath: string): string {
  return imagePath.replace(/^manga_outputs\//, '');
}

function encodeStaticPath(imagePath: string): string {
  return mangaStaticPath(imagePath).split('/').map(encodeURIComponent).join('/');
}

export function mangaThumbUrl(imagePath: string | null | undefined, width = 720, cacheBust?: string | number): string | null {
  if (!imagePath) return null;
  const url = `${BASE}/static/manga/_thumb/${encodeStaticPath(imagePath)}?w=${width}`;
  return cacheBust ? `${url}&v=${encodeURIComponent(String(cacheBust))}` : url;
}

export function coverImageUrl(coverPath: string | null | undefined): string | null {
  if (!coverPath) return null;
  return `${BASE}/static/manga/${mangaStaticPath(coverPath)}`;
}

// ─── Chapter ────────────────────────────────────────────────

export async function getChapter(chapterId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function listChapters(storyId: number): Promise<Chapter[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function createNextChapter(storyId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteChapter(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}

// ─── Chat (SSE) ─────────────────────────────────────────────

export function chatStream(
  chapterId: number,
  content: string,
  onToken: (token: string) => void,
  _onDone: (fullContent: string) => void,
  onError: (err: string) => void,
): AbortController {
  const controller = new AbortController();

  authFetch(`${BASE}/api/chapters/${chapterId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: content }),
    signal: controller.signal,
  })
    .then(async (res) => {
      if (!res.ok) {
        onError(await res.text());
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trim();
          try {
            const data = JSON.parse(dataStr);
            if (currentEvent === 'token' && data.content !== undefined) {
              onToken(data.content);
            } else if (currentEvent === 'done' && data.content !== undefined) {
              _onDone(data.content);
            } else if (currentEvent === 'error' || data.error) {
              onError(data.error);
            }
          } catch {
            // ignore
          }
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);

      // If stream ended without a done event, call onDone with empty
      // This handles edge cases where connection closes unexpectedly
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onError(err.message);
      }
    });

  return controller;
}

// ─── Generate Novel ─────────────────────────────────────────

export async function generateNovel(chapterId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-novel`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function importNovel(chapterId: number, content: string): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/import-novel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// ─── Scenes ─────────────────────────────────────────────────

export async function generateScenes(chapterId: number, signal?: AbortSignal): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-scenes`, { method: 'POST', signal });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function getScenes(chapterId: number): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function updateScenes(chapterId: number, scenes: string[]): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scenes),
  });
  if (!res.ok) throw new Error(await res.text());
}

// ─── Character Profiles ─────────────────────────────────────

// Story-level (global)
export async function getStoryCharacters(storyId: number): Promise<string> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.characters;
}

export async function saveStoryCharacters(storyId: number, characters: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characters }),
  });
  if (!res.ok) throw new Error(await res.text());
}

// Chapter-level (with source info)
export type CharacterSource = 'chapter' | 'asset_group' | 'story' | 'none';

export async function getCharacters(chapterId: number): Promise<{ characters: string; source: CharacterSource; group_id?: number; group_name?: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`);
  if (!res.ok) throw new Error(await res.text());
  return await res.json();
}

export async function saveCharacters(chapterId: number, characters: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characters }),
  });
  if (!res.ok) throw new Error(await res.text());
}

export async function resetChapterCharacters(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}

// ─── Reference Images (垫图，支持多图) ────────────────────────

export type RefSource = 'chapter' | 'asset_group' | 'story' | 'none';

export interface RefImage {
  filename: string;
  image_path: string;
  size_kb: number;
}

export interface RefImagesPayload {
  images: RefImage[];
  max: number;
  source?: RefSource;
  group_id?: number;
  group_name?: string;
}

export function refImageUrl(imagePath: string): string {
  return `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
}

export interface AssetGroup {
  id: number | null;
  name: string;
  is_default: boolean;
  character_profiles: string;
  has_character_profiles: boolean;
  ref_images: RefImage[];
  ref_count: number;
}

export interface AssetGroupsPayload {
  groups: AssetGroup[];
  max: number;
  selected_group_id?: number | null;
}

export async function getStoryAssetGroups(storyId: number): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function createStoryAssetGroup(storyId: number, name: string): Promise<{ group: AssetGroup; groups: AssetGroup[] }> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error(await res.text());
  const group = await res.json();
  const groups = (await getStoryAssetGroups(storyId)).groups;
  return { group, groups };
}

export async function updateStoryAssetGroup(storyId: number, groupId: number, data: { name?: string; characters?: string }): Promise<{ group: AssetGroup; groups: AssetGroup[] }> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  const group = await res.json();
  const groups = (await getStoryAssetGroups(storyId)).groups;
  return { group, groups };
}

export async function deleteStoryAssetGroup(storyId: number, groupId: number): Promise<{ groups: AssetGroup[] }> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
  const groups = (await getStoryAssetGroups(storyId)).groups;
  return { groups };
}

export async function addStoryAssetGroupRefImage(storyId: number, groupId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryAssetGroupRefImage(storyId: number, groupId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function getChapterAssetGroup(chapterId: number): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function setChapterAssetGroup(chapterId: number, groupId: number | null): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ group_id: groupId }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// Story-level
export async function getStoryRefImages(storyId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function addStoryRefImage(storyId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryRefImage(storyId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// Chapter-level (with story fallback)
export async function getChapterRefImages(chapterId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function addChapterRefImage(chapterId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteChapterRefImage(chapterId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/chapters/${chapterId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// ─── Color Mode ─────────────────────────────────────────────

export type ColorMode = 'bw' | 'color';

export async function getColorMode(chapterId: number): Promise<ColorMode> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.color_mode || 'bw';
}

export async function setColorMode(chapterId: number, mode: ColorMode): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ color_mode: mode }),
  });
  if (!res.ok) throw new Error(await res.text());
}

// ─── Image Count ─────────────────────────────────────────────

export const ALLOWED_IMAGE_COUNTS = [4, 6, 8, 10, 12, 15, 20] as const;

export async function getImageCount(chapterId: number): Promise<number> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/image-count`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.image_count ?? 10;
}

export async function setImageCount(chapterId: number, count: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/image-count`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image_count: count }),
  });
  if (!res.ok) throw new Error(await res.text());
}

export async function regenerateImage(
  chapterId: number,
  imageNumber: number,
  prompt: string,
): Promise<{ id: number; image_number: number; image_path: string; prompt: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/regenerate-image/${imageNumber}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// ─── Generate Manga (SSE with progress) ─────────────────────

export interface MangaProgress {
  type: 'status' | 'scenes' | 'progress' | 'image' | 'done' | 'error';
  data: any;
}

export function generateMangaStream(
  chapterId: number,
  onEvent: (event: MangaProgress) => void,
): AbortController {
  const controller = new AbortController();
  let reconnectAttempts = 0;
  let reconnectTimer: number | undefined;
  const maxReconnectAttempts = 120;

  const scheduleReconnect = (reason: string) => {
    if (controller.signal.aborted) return;
    reconnectAttempts += 1;
    if (reconnectAttempts > maxReconnectAttempts) {
      onEvent({ type: 'error', data: { error: reason || '生成连接已断开，请稍后重试' } });
      return;
    }
    onEvent({
      type: 'status',
      data: { message: `连接中断，正在重连...（${reconnectAttempts}/${maxReconnectAttempts}）` },
    });
    reconnectTimer = window.setTimeout(connect, Math.min(5000, 1000 + reconnectAttempts * 500));
  };

  controller.signal.addEventListener('abort', () => {
    if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer);
  });

  const connect = () => {
    authFetch(`${BASE}/api/chapters/${chapterId}/generate-manga-stream`, {
      method: 'POST',
      signal: controller.signal,
    })
      .then(async (res) => {
      if (!res.ok) {
        onEvent({ type: 'error', data: { error: await res.text() } });
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      let receivedTerminalEvent = false;
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trim();
          try {
            const data = JSON.parse(dataStr);
            const eventType = currentEvent as MangaProgress['type'];
            if (eventType === 'done' || eventType === 'error') receivedTerminalEvent = true;
            onEvent({ type: eventType, data });
          } catch {
            // ignore unparseable data
          }
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);
      if (!receivedTerminalEvent && !controller.signal.aborted) {
        scheduleReconnect('生成连接已断开，请稍后重试');
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        scheduleReconnect(err.message || '生成连接已断开，请稍后重试');
      }
    });
  };

  connect();

  return controller;
}

export function mangaImageUrl(imagePath: string, cacheBust?: number): string {
  // imagePath is like "manga_outputs/chapter_1/panel_01_abc12345.png"
  // Served at /static/manga/chapter_1/panel_01_abc12345.png
  const url = `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
  return cacheBust ? `${url}?t=${cacheBust}` : url;
}
