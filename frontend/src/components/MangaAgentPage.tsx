import { useEffect, useRef, useState } from 'react';
import {
  Archive,
  Bot,
  BookOpenText,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Loader2,
  MessageCircleQuestion,
  MessageSquareText,
  Plus,
  Send,
  Sparkles,
  Square,
  TriangleAlert,
  Wrench,
} from 'lucide-react';
import ModelSwitcher from './ModelSwitcher';
import {
  API_KEY_CHANGE_EVENT,
  cancelMangaAgentConversationRun,
  createMangaAgentConversation,
  deleteMangaAgentConversation,
  getPrimaryProviderModel,
  getProviderModelOptions,
  getMangaAgentConversationMessages,
  getOpenMangaAgentConversationRun,
  listChapters,
  listMangaAgentConversations,
  listStories,
  replayMangaAgentRunEvents,
  resumeMangaAgentAgUiStream,
  runMangaAgentAgUiStream,
  type AgentUserInputRequest,
  type Chapter,
  type MangaAgentConversation,
  type MangaAgentMessage,
  type MangaAgentRunSnapshot,
  type Story,
} from '../api';
import MarkdownRenderer from './MarkdownRenderer';

interface Message {
  role: 'user' | 'assistant' | 'system';
  content: string;
  requestId?: string;
}

type ExecutionTone = 'neutral' | 'thinking' | 'tool' | 'waiting' | 'success' | 'warning' | 'error';
type AgUiEventTone = ExecutionTone | 'info';

interface ConversationView extends MangaAgentConversation {
  isActive?: boolean;
}

interface ConversationCacheEntry {
  messages: Message[];
  runSnapshot: MangaAgentRunSnapshot | null;
}

interface ExecutionEventItem {
  id: string;
  type: string;
  title: string;
  detail: string;
  createdAt?: string;
  tone: AgUiEventTone;
  icon: 'bot' | 'sparkles' | 'wrench' | 'question' | 'check' | 'warning' | 'clock' | 'message' | 'archive';
}

/* ------------------------------------------------------------------ */
/*  Lightweight upward-opening select                                 */
/* ------------------------------------------------------------------ */

function SelectUpward<T extends string>({
  value,
  label,
  options,
  onChange,
  disabled,
  width,
}: {
  value: T;
  label: string;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  disabled?: boolean;
  width: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [open]);

  const display = options.find(o => o.value === value)?.label || label;

  return (
    <div ref={ref} className={`relative ${width} shrink-0`}>
      <button
        type="button"
        disabled={disabled}
        onClick={() => { if (!disabled) setOpen(v => !v); }}
        className={`flex w-full items-center gap-1 truncate rounded-xl border px-3 py-1.5 text-xs font-medium transition outline-none ${open ? 'border-accent/40 bg-accent-muted/10 text-accent' : 'border-border bg-bg-surface/80 text-text-primary hover:border-accent/30'}`}
      >
        <span className="truncate flex-1 text-left">{display}</span>
        <svg className={`shrink-0 text-text-muted transition-transform ${open ? 'rotate-180' : ''}`} width="12" height="12" viewBox="0 0 20 20" fill="none"><path stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M6 8l4 4 4-4"/></svg>
      </button>
      {open && (
        <div className="absolute bottom-[calc(100%+4px)] left-0 z-40 w-full max-h-[200px] overflow-y-auto overscroll-contain rounded-xl border border-border bg-bg-raised shadow-lg animate-fade-in p-1">
          {options.map(o => {
            const sel = o.value === value;
            return (
              <button
                key={o.value}
                type="button"
                onClick={() => { onChange(o.value); setOpen(false); }}
                className={`w-full truncate rounded-lg px-2.5 py-2 text-xs text-left transition-colors ${sel ? 'bg-accent-soft text-accent font-medium' : 'text-text-primary hover:bg-bg-surface'}`}
              >
                {o.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function routeLabel(route?: string): string {
  switch (route) {
    case 'CONVERSATION': return '对话';
    case 'CREATIVE': return '创意顾问';
    case 'STORYBOARD': return '分镜';
    case 'REVIEW': return '审查';
    case 'DIRECTOR': return '导演';
    default: return '智能体';
  }
}

function conversationStatusLabel(status?: string): string {
  return status === 'ACTIVE' ? '进行中' : status === 'ARCHIVED' ? '已归档' : '未知';
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function formatRequestId(requestId: string): string {
  return requestId.length <= 18 ? requestId : `${requestId.slice(0, 8)}...${requestId.slice(-6)}`;
}

function formatTimestamp(value?: string): string {
  if (!value) return '';
  try {
    return new Date(value).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return value;
  }
}

function normalizeConversationId(value: unknown): string {
  return typeof value === 'string' && value !== 'undefined' && value.trim() ? value : '';
}

function normalizeConversation(item: any): ConversationView {
  const status = String(item?.status || 'ARCHIVED');
  const conversationId = normalizeConversationId(item?.conversationId ?? item?.conversation_id);
  return {
    ...item,
    conversationId,
    title: String(item?.title || ''),
    status: status === 'ACTIVE' ? 'ACTIVE' : 'ARCHIVED',
    createdAt: item?.createdAt ?? item?.created_at,
    updatedAt: item?.updatedAt ?? item?.updated_at,
    archivedAt: item?.archivedAt ?? item?.archived_at ?? null,
    isActive: status === 'ACTIVE',
  };
}

function normalizeConversationList(items: MangaAgentConversation[]): ConversationView[] {
  return items.map((item) => normalizeConversation(item));
}

function toMessages(items: MangaAgentMessage[]): Message[] {
  return items.flatMap((item) => {
    if (item.role !== 'user' && item.role !== 'assistant' && item.role !== 'system') return [];
    if (!item.content) return [];
    return [{
      role: item.role,
      content: item.content,
      requestId: item.requestId ?? item.request_id,
    }];
  });
}

function appendExecutionEvent(events: ExecutionEventItem[], event: ExecutionEventItem): ExecutionEventItem[] {
  const last = events[events.length - 1];
  if (last && last.type === event.type && last.title === event.title && last.detail === event.detail) {
    return events;
  }
  return [...events, event].slice(-30);
}

function asRecord(value: unknown): Record<string, any> {
  return value && typeof value === 'object' ? value as Record<string, any> : {};
}

function inferExecutionEvent(event: Record<string, any>): ExecutionEventItem {
  const type = String(event.type || 'event');
  const createdAt = typeof event.timestamp === 'number'
    ? new Date(event.timestamp).toISOString()
    : typeof event.createdAt === 'string'
      ? event.createdAt
      : undefined;

  if (type === 'RUN_STARTED') {
    const message = String(event.input?.state?.message || event.input?.message || '智能体已启动');
    const route = String(event.input?.state?.route || event.route || '');
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '智能体已启动',
      detail: route ? `正在执行 ${routeLabel(route)} 模式：${message}` : message,
      createdAt,
      tone: 'thinking',
      icon: 'bot',
    };
  }

  if (type === 'STATE_SNAPSHOT') {
    const snapshot = asRecord(event.snapshot);
    const status = String(snapshot.status || event.status || 'RUNNING');
    const message = String(snapshot.message || '智能体正在处理中');
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '状态快照',
      detail: `${status} · ${message}`,
      createdAt,
      tone: status.includes('WAITING') ? 'waiting' : 'neutral',
      icon: status.includes('WAITING') ? 'question' : 'clock',
    };
  }

  if (type === 'TEXT_MESSAGE_START' || type === 'TEXT_MESSAGE_CONTENT' || type === 'TEXT_MESSAGE_END') {
    const delta = String(event.delta || event.content || event.text || '');
    return {
      id: `${type}-${createdAt || Date.now()}-${String(event.messageId || '')}`,
      type,
      title: type === 'TEXT_MESSAGE_START' ? '文本输出开始' : type === 'TEXT_MESSAGE_END' ? '文本输出结束' : '文本流输出',
      detail: delta ? delta : '智能体正在生成回复',
      createdAt,
      tone: 'neutral',
      icon: 'message',
    };
  }

  if (type === 'CUSTOM') {
    const name = String(event.name || '自定义事件');
    const value = asRecord(event.value);
    const data = asRecord(value.data);
    const tool = String(value.tool || value.toolName || '');
    const label = String(value.label || value.title || name);
    const detailParts: string[] = [];
    if (tool) detailParts.push(`工具：${tool}`);
    if (value.status) detailParts.push(`状态：${value.status}`);
    if (data.status && !value.status) detailParts.push(`状态：${data.status}`);
    if (data.node) detailParts.push(`节点：${data.node}`);
    if (data.route) detailParts.push(`模式：${routeLabel(String(data.route))}`);
    if (Array.isArray(data.warnings) && data.warnings.length) detailParts.push(`警告：${data.warnings.join('；')}`);
    return {
      id: `${type}-${name}-${createdAt || Date.now()}`,
      type,
      title: label,
      detail: detailParts.length > 0 ? detailParts.join(' · ') : label,
      createdAt,
      tone: tool || name.includes('tool') ? 'tool' : 'neutral',
      icon: tool || name.includes('tool') ? 'wrench' : 'sparkles',
    };
  }

  if (type === 'RUN_FINISHED') {
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '运行完成',
      detail: '回复已保存到对话',
      createdAt,
      tone: 'success',
      icon: 'check',
    };
  }

  if (type === 'RUN_ERROR') {
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '运行失败',
      detail: String(event.message || event.error || '智能体运行出现错误'),
      createdAt,
      tone: 'error',
      icon: 'warning',
    };
  }

  if (type === 'RUN_INTERRUPTED') {
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '运行中断',
      detail: String(event.message || '本次运行已被中断'),
      createdAt,
      tone: 'warning',
      icon: 'warning',
    };
  }

  return {
    id: `${type}-${createdAt || Date.now()}`,
    type,
    title: type,
    detail: '已收到事件',
    createdAt,
    tone: 'info',
    icon: 'clock',
  };
}

function executionBadgeClass(tone: AgUiEventTone): string {
  return {
    info: 'border-border bg-bg-surface text-text-secondary',
    neutral: 'border-border bg-bg-surface text-text-secondary',
    thinking: 'border-accent-secondary/30 bg-accent-secondary/10 text-accent-secondary',
    tool: 'border-accent-tertiary/30 bg-accent-tertiary/10/50 text-accent-tertiary',
    waiting: 'border-accent/30 bg-accent-muted/50 text-accent',
    success: 'border-success/30 bg-success/10 text-success',
    warning: 'border-warning/30 bg-warning/10 text-warning',
    error: 'border-accent/30 bg-accent-muted/30 text-accent',
  }[tone];
}

function executionIcon(tone: AgUiEventTone, icon: ExecutionEventItem['icon']) {
  const className = {
    info: 'text-text-secondary',
    neutral: 'text-text-secondary',
    thinking: 'text-accent-secondary',
    tool: 'text-accent-tertiary',
    waiting: 'text-accent',
    success: 'text-success',
    warning: 'text-warning',
    error: 'text-accent',
  }[tone];
  const size = 15;
  switch (icon) {
    case 'bot': return <Bot size={size} className={className} />;
    case 'sparkles': return <Sparkles size={size} className={className} />;
    case 'wrench': return <Wrench size={size} className={className} />;
    case 'question': return <MessageCircleQuestion size={size} className={className} />;
    case 'check': return <CheckCircle2 size={size} className={className} />;
    case 'warning': return <TriangleAlert size={size} className={className} />;
    case 'message': return <MessageSquareText size={size} className={className} />;
    case 'archive': return <Archive size={size} className={className} />;
    case 'clock':
    default:
      return <Clock3 size={size} className={className} />;
  }
}

export default function MangaAgentPage({ onCreateStory }: { onCreateStory?: () => void }) {
  const [stories, setStories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [storyId, setStoryId] = useState('');
  const [chapterId, setChapterId] = useState('');
  const [conversations, setConversations] = useState<ConversationView[]>([]);
  const [conversationId, setConversationId] = useState('');
  const [selectedLlmModel, setSelectedLlmModel] = useState(() => getPrimaryProviderModel('llm'));
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [bootLoading, setBootLoading] = useState(true);
  const [chapterLoading, setChapterLoading] = useState(false);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [userInputRequest, setUserInputRequest] = useState<AgentUserInputRequest | null>(null);
  const [customAnswer, setCustomAnswer] = useState('');
  const [draftReply, setDraftReply] = useState('');
  const [runStatus, setRunStatus] = useState('尚未开始运行');
  const [businessStatus, setBusinessStatus] = useState('');
  const [lastProgressAt, setLastProgressAt] = useState('');
  const [currentPhase, setCurrentPhase] = useState('');
  const [activeRoute, setActiveRoute] = useState('');
  const [routeConfidence, setRouteConfidence] = useState<number | null>(null);
  const [executionEvents, setExecutionEvents] = useState<ExecutionEventItem[]>([]);
  const [activeRequestId, setActiveRequestId] = useState<string | null>(null);
  const [pendingConversationId, setPendingConversationId] = useState<string | null>(null);
  const chapterIdRef = useRef('');
  const conversationIdRef = useRef('');
  const conversationLoadSeqRef = useRef(0);
  const activeRunConversationIdRef = useRef('');
  const activeStreamControllerRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const conversationCacheRef = useRef<Record<string, ConversationCacheEntry>>({});


  const latestExecutionEvent = executionEvents.length > 0 ? executionEvents[executionEvents.length - 1] : null;
  const showExecutionPanel = executionEvents.length > 0 || !!userInputRequest || !!draftReply || !!activeRequestId || sending;
  const waitingForHuman = !!userInputRequest;

  useEffect(() => { chapterIdRef.current = chapterId; }, [chapterId]);
  useEffect(() => { conversationIdRef.current = conversationId; }, [conversationId]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages, executionEvents, draftReply, userInputRequest, sending]);
  useEffect(() => () => { activeStreamControllerRef.current?.abort(); }, []);

  useEffect(() => {
    const syncModels = () => {
      const options = getProviderModelOptions('llm');
      setSelectedLlmModel((prev) => (prev && options.includes(prev) ? prev : (options[0] || '')));
    };
    syncModels();
    window.addEventListener(API_KEY_CHANGE_EVENT, syncModels);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, syncModels);
  }, []);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setBootLoading(true);
      try {
        const list = await listStories();
        if (!active) return;
        setStories(list);
        if (list.length > 0) setStoryId(String(list[0].id));
      } catch (err: any) {
        if (active) setError(err.message || '加载会话失败');
      } finally {
        if (active) setBootLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    conversationCacheRef.current = {};
    setPendingConversationId(null);
  }, [storyId, chapterId]);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setConversationLoading(false);
      setConversations([]);
      setConversationId('');
      setPendingConversationId(null);
      setMessages([]);
      setExecutionEvents([]);
      setDraftReply('');
      setUserInputRequest(null);
      setCustomAnswer('');
      setRunStatus('尚未开始运行');
      setBusinessStatus('');
      setLastProgressAt('');
      setCurrentPhase('');
      setActiveRequestId(null);
      conversationCacheRef.current = {};
      if (!storyId) return;

      setChapterLoading(true);
      setError('');
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      try {
        const chapterList = await listChapters(Number(storyId));
        if (!active) return;
        setChapters(chapterList);
        setChapterId((prev) => (prev && chapterList.some((item) => String(item.id) === prev) ? prev : (chapterList[0] ? String(chapterList[0].id) : '')));
      } catch (err: any) {
        if (active) setError(err.message || '加载会话失败');
      } finally {
        if (active) setChapterLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [storyId]);

  useEffect(() => {
    let active = true;
    const load = async () => {
      if (!chapterId) return;
      setConversationLoading(true);
      setError('');
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      try {
        const id = Number(chapterId);
        const conversationList = normalizeConversationList(await listMangaAgentConversations(id));
        if (!active) return;
        setConversations(conversationList);
        let selected = conversationList.find((item) => item.status === 'ACTIVE') ?? conversationList[0] ?? null;
        if (!selected) {
          selected = await createMangaAgentConversation(id);
        }
        if (!active || !selected) return;
        const resolvedConversationId = normalizeConversationId((selected as any)?.conversationId ?? (selected as any)?.conversation_id);
        if (!resolvedConversationId) {
          throw new Error('会话标识缺失，请刷新页面后重试');
        }
        await loadConversation(id, resolvedConversationId);
        const refreshed = normalizeConversationList(await listMangaAgentConversations(id));
        if (!active) return;
        setConversations(refreshed);
      } catch (err: any) {
        if (active) setError(err.message || '加载会话失败');
      } finally {
        if (active) setConversationLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [chapterId]);

  async function loadConversation(chapterNumericId: number, selectedConversationId: string) {
    const loadSeq = ++conversationLoadSeqRef.current;
    activeStreamControllerRef.current?.abort();
    activeStreamControllerRef.current = null;
    activeRunConversationIdRef.current = '';
    setPendingConversationId(selectedConversationId);
    setError('');
    setRunStatus(`正在切换到：${conversations.find((item) => item.conversationId === selectedConversationId)?.title || '会话'}`);
    const cached = conversationCacheRef.current[selectedConversationId];
    if (cached) {
      if (loadSeq !== conversationLoadSeqRef.current) return;
        setConversationId(selectedConversationId);
      setMessages(cached.messages);
      if (cached.runSnapshot) restoreRunSnapshot(cached.runSnapshot);
      else {
        setExecutionEvents([]);
        setDraftReply('');
        setUserInputRequest(null);
        setBusinessStatus('');
        setLastProgressAt('');
        setCurrentPhase('');
        setActiveRoute('');
        setRouteConfidence(null);
        setActiveRequestId(null);
        setRunStatus('会话已就绪');
      }
      if (cached.runSnapshot?.status === 'RUNNING') {
        attachReplayStream(chapterNumericId, selectedConversationId, cached.runSnapshot);
      }
      setPendingConversationId(null);
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
      return;
    }

    setConversationId(selectedConversationId);
    setMessages([]);
    setExecutionEvents([]);
    setDraftReply('');
    setUserInputRequest(null);
    setCustomAnswer('');
    setBusinessStatus('');
    setLastProgressAt('');
    setCurrentPhase('');
    setActiveRoute('');
    setRouteConfidence(null);
    setActiveRequestId(null);
    try {
      const [list, openRun] = await Promise.all([
        getMangaAgentConversationMessages(chapterNumericId, selectedConversationId),
        getOpenMangaAgentConversationRun(chapterNumericId, selectedConversationId),
      ]);
      if (loadSeq !== conversationLoadSeqRef.current) return;
      const nextMessages = toMessages(list);
      setMessages(nextMessages);
      const snapshot = openRun || null;
      conversationCacheRef.current[selectedConversationId] = { messages: nextMessages, runSnapshot: snapshot };
      if (snapshot) {
        restoreRunSnapshot(snapshot);
        if (snapshot.status === 'RUNNING') {
          attachReplayStream(chapterNumericId, selectedConversationId, snapshot);
        }
      } else {
        setRunStatus('会话已就绪');
      }
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    } finally {
      if (loadSeq === conversationLoadSeqRef.current) {
        setPendingConversationId(null);
      }
    }
  }

  function restoreRunSnapshot(snapshot: MangaAgentRunSnapshot) {
    setActiveRequestId(snapshot.requestId ?? snapshot.request_id ?? null);
    setUserInputRequest(snapshot.userInputRequest ?? null);
    setRunStatus(snapshot.status === 'WAITING_USER' ? '等待用户确认' : `业务状态：${snapshot.status}`);
    setBusinessStatus(snapshot.status);
    setLastProgressAt(snapshot.lastProgressAt || '');
    setCurrentPhase(snapshot.currentPhase || '');
    setActiveRoute(snapshot.route || '');
    setRouteConfidence(typeof snapshot.routeConfidence === 'number' ? snapshot.routeConfidence : null);
    setDraftReply(snapshot.finalReply || '');
    setExecutionEvents((snapshot.events || []).map((event) => {
      const payload = asRecord(event.data);
      return inferExecutionEvent({
        type: String((event as any).eventName || payload.type || 'event'),
        ...payload,
        createdAt: event.createdAt,
      });
    }));
  }

  function attachReplayStream(
    chapterNumericId: number,
    selectedConversationId: string,
    snapshot: MangaAgentRunSnapshot,
  ) {
    const requestId = snapshot.requestId ?? snapshot.request_id;
    if (!requestId) return;
    const lastEventId = Math.max(0, ...(snapshot.events || []).map((event) => event.eventId || 0));
    activeRunConversationIdRef.current = selectedConversationId;
    activeStreamControllerRef.current = replayMangaAgentRunEvents(
      chapterNumericId,
      requestId,
      lastEventId,
      (event) => handleStreamEvent(event),
    );
  }

  function recordAgUiEvent(rawEvent: Record<string, any>) {
    const executionEvent = inferExecutionEvent(rawEvent);
    setExecutionEvents((prev) => appendExecutionEvent(prev, executionEvent));
    setLastProgressAt(new Date().toISOString());

    if (rawEvent.type === 'STATE_SNAPSHOT') {
      const snapshot = asRecord(rawEvent.snapshot);
      const status = String(snapshot.status || rawEvent.status || '');
      const message = String(snapshot.message || rawEvent.message || '');
      if (status) setBusinessStatus(status);
      if (message) setRunStatus(message);
      else if (status) setRunStatus(`业务状态：${status}`);
      if (snapshot.route) setActiveRoute(String(snapshot.route));
    }

    if (rawEvent.type === 'RUN_STARTED') {
      const message = String(rawEvent.input?.state?.message || rawEvent.input?.message || '智能体已启动');
      setRunStatus(message);
      setBusinessStatus('RUNNING');
      const route = rawEvent.input?.state?.route || rawEvent.route;
      if (route) setActiveRoute(String(route));
    }

    if (rawEvent.type === 'CUSTOM') {
      const value = asRecord(rawEvent.value);
      const data = asRecord(value.data);
      const status = String(value.status || '');
      if (status) setBusinessStatus(status);
      if (value.message) setRunStatus(String(value.message));
      if (data.route) setActiveRoute(String(data.route));
      if (typeof data.confidence === 'number') setRouteConfidence(data.confidence);
    }

    if (rawEvent.type === 'TEXT_MESSAGE_CONTENT' || rawEvent.type === 'TEXT_MESSAGE_START') {
      const delta = String(rawEvent.delta || rawEvent.content || rawEvent.text || '');
      if (delta) setDraftReply((prev) => prev + delta);
    }

    if (rawEvent.type === 'TEXT_MESSAGE_END') {
      const message = String(rawEvent.message || '');
      if (message && !draftReply) setDraftReply(message);
    }

    if (rawEvent.type === 'RUN_FINISHED') {
      const reply = String(rawEvent.result?.reply || '');
      if (reply) setDraftReply(reply);
      setBusinessStatus('SUCCEEDED');
      setRunStatus('运行已完成');
      setUserInputRequest(null);
    }

    if (rawEvent.type === 'RUN_ERROR') {
      const message = String(rawEvent.message || rawEvent.error || '智能体运行失败');
      setRunStatus(message);
      setError(message);
      setSending(false);
      setBusinessStatus('FAILED');
    }
  }

  function handleAgUiEvent(event: { type?: string; [key: string]: any }): void {
    recordAgUiEvent(event);

    if (event.type === 'RUN_FINISHED') {
      void refreshMessagesAfterRun();
    }

    if (event.type === 'RUN_ERROR') {
      setSending(false);
    }

    if (event.type === 'RUN_STARTED' || event.type === 'STATE_SNAPSHOT' || event.type === 'CUSTOM') {
      setSending(true);
    }

    if (event.type === 'STATE_SNAPSHOT' && event.snapshot?.status === 'WAITING_USER') {
      setSending(false);
      setUserInputRequest(event.outcome?.interrupts?.[0]?.metadata || null);
    }

    if (event.type === 'STATE_SNAPSHOT'
      && event.snapshot?.status
      && event.snapshot.status !== 'RUNNING'
      && event.snapshot.status !== 'WAITING_USER') {
      setSending(false);
      void refreshMessagesAfterRun();
    }

    if (event.type === 'RUN_FINISHED') {
      setSending(false);
    }
  }

  async function refreshMessagesAfterRun() {
    const chapterNumericId = Number(chapterIdRef.current);
    const selectedConversationId = normalizeConversationId(activeRunConversationIdRef.current || conversationIdRef.current);
    if (!chapterNumericId || !selectedConversationId) return;
    try {
      const list = await getMangaAgentConversationMessages(chapterNumericId, selectedConversationId);
      setMessages(toMessages(list));
      setDraftReply('');
      // Clear runtime state after run finishes — the reply is persisted in messages,
      // and clearing events/requestId hides the execution panel naturally.
      setExecutionEvents([]);
      setActiveRequestId(null);
    } catch {
      // ignore refresh failure; live state still exists
    }
  }

  function handleStreamEvent(event: any): void {
    if (!event) return;

    if (event.type === 'ag_ui_event') {
      handleAgUiEvent(event.data || {});
      return;
    }

    if (event.type === 'status') {
      setRunStatus(event.data?.message || '智能体正在运行');
      setBusinessStatus('RUNNING');
      return;
    }

    if (event.type === 'run_event') {
      const payload = asRecord(event.data);
      recordAgUiEvent({
        type: String(payload.type || 'CUSTOM'),
        ...payload,
      });
      return;
    }

    if (event.type === 'tool') {
      recordAgUiEvent({
        type: 'CUSTOM',
        name: 'tool_audit',
        value: event.data || {},
      });
      return;
    }

    if (event.type === 'user_input_requested') {
      setUserInputRequest(event.data);
      setRunStatus(event.data.question || '需要你做出选择');
      setBusinessStatus('WAITING_USER');
      setSending(false);
      return;
    }

    if (event.type === 'done') {
      recordAgUiEvent({
        type: 'RUN_FINISHED',
        result: { reply: event.data?.reply || '' },
      });
      return;
    }

    if (event.type === 'error') {
      const message = event.data?.detail || event.data?.error || '智能体请求失败';
      recordAgUiEvent({
        type: 'RUN_ERROR',
        message,
      });
      setError(message);
      setSending(false);
    }
  }

  async function startRun(message?: string) {
    const chapterNumericId = Number(chapterId);
    const selectedConversationId = normalizeConversationId(conversationId);
    if (!chapterNumericId || !selectedConversationId || sending) return;
    const text = (message ?? input).trim();
    if (!text) return;
    setError('');
    setSending(true);
    setRunStatus('正在启动运行...');
    setBusinessStatus('RUNNING');
    setDraftReply('');
    setUserInputRequest(null);
    setExecutionEvents([]);
    const requestId = createRequestId();
    setActiveRequestId(requestId);
    activeRunConversationIdRef.current = selectedConversationId;
    setMessages((prev) => [...prev, { role: 'user', content: text, requestId }]);
    setInput('');
    activeStreamControllerRef.current?.abort();
    activeStreamControllerRef.current = runMangaAgentAgUiStream(
      chapterNumericId,
      text,
      requestId,
      (event) => handleStreamEvent(event),
      selectedConversationId,
      selectedLlmModel,
    );
  }

  async function resumeWithAnswer(answer: string) {
    const chapterNumericId = Number(chapterId);
    const selectedConversationId = normalizeConversationId(conversationId);
    const requestId = userInputRequest?.requestId ?? userInputRequest?.request_id ?? activeRequestId;
    if (!chapterNumericId || !selectedConversationId || !requestId || sending) return;
    const text = answer.trim();
    if (!text) return;
    setError('');
    setSending(true);
    setRunStatus('正在提交回答...');
    setBusinessStatus('RUNNING');
    setCustomAnswer('');
    setUserInputRequest(null);
    activeRunConversationIdRef.current = selectedConversationId;
    activeStreamControllerRef.current?.abort();
    activeStreamControllerRef.current = resumeMangaAgentAgUiStream(
      chapterNumericId,
      requestId,
      text,
      (event) => handleStreamEvent(event),
      selectedConversationId,
      selectedLlmModel,
    );
    setMessages((prev) => [...prev, { role: 'system', content: `已提交回答：${text}`, requestId }]);
  }

  async function cancelActiveRun() {
    const chapterNumericId = Number(chapterId);
    const selectedConversationId = normalizeConversationId(conversationId);
    const requestId = activeRequestId;
    if (!chapterNumericId || !selectedConversationId || !requestId) return;
    setError('');
    setRunStatus('正在停止运行...');
    try {
      const snapshot = await cancelMangaAgentConversationRun(chapterNumericId, selectedConversationId, requestId);
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      activeRunConversationIdRef.current = '';
      restoreRunSnapshot(snapshot);
      setSending(false);
      setRunStatus('本次运行已停止');
    } catch (err: any) {
      setError(err.message || '停止运行失败');
    }
  }

  async function loadSelectedConversation(nextConversationId: string) {
    const chapterNumericId = Number(chapterId);
    if (!chapterNumericId || !nextConversationId) return;
    try {
      setConversationLoading(true);
      await loadConversation(chapterNumericId, nextConversationId);
    } catch (err: any) {
      setError(err.message || '切换会话失败');
    } finally {
      setConversationLoading(false);
    }
  }

  async function startNewConversation() {
    const chapterNumericId = Number(chapterId);
    if (!chapterNumericId) return;
    setError('');
    setConversationLoading(true);
    try {
      const conversation = normalizeConversation(await createMangaAgentConversation(chapterNumericId));
      const resolvedConversationId = normalizeConversationId(conversation.conversationId);
      if (!resolvedConversationId) throw new Error('会话标识缺失，请刷新页面后重试');
      const refreshed = normalizeConversationList(await listMangaAgentConversations(chapterNumericId));
      setConversations(refreshed);
      setPendingConversationId(resolvedConversationId);
      await loadConversation(chapterNumericId, resolvedConversationId);
      setRunStatus('会话已就绪');
    } catch (err: any) {
      setError(err.message || '开启新会话失败');
    } finally {
      setConversationLoading(false);
    }
  }

  async function deleteConversation(conversationToDelete: ConversationView) {
    const chapterNumericId = Number(chapterId);
    const targetConversationId = normalizeConversationId(conversationToDelete.conversationId);
    if (!chapterNumericId || !targetConversationId) return;
    if (!window.confirm(`确定删除会话「${conversationToDelete.title || '未命名会话'}」吗？删除后无法恢复。`)) return;
    setError('');
    setConversationLoading(true);
    try {
      await deleteMangaAgentConversation(chapterNumericId, targetConversationId);
      delete conversationCacheRef.current[targetConversationId];
      const nextConversations = normalizeConversationList(await listMangaAgentConversations(chapterNumericId));
      setConversations(nextConversations);

      if (conversationIdRef.current === targetConversationId) {
        const nextSelected = nextConversations.find((item) => item.status === 'ACTIVE') ?? nextConversations[0] ?? null;
        if (nextSelected) {
          await loadConversation(chapterNumericId, nextSelected.conversationId);
        } else {
          const created = normalizeConversation(await createMangaAgentConversation(chapterNumericId));
          const createdId = normalizeConversationId(created.conversationId);
          if (createdId) {
            const afterCreate = normalizeConversationList(await listMangaAgentConversations(chapterNumericId));
            setConversations(afterCreate);
            await loadConversation(chapterNumericId, createdId);
          }
        }
      }
    } catch (err: any) {
      setError(err.message || '删除会话失败');
    } finally {
      setConversationLoading(false);
    }
  }

  const showWelcome = !bootLoading && stories.length === 0;

  if (showWelcome) {
    return (
      <div className="flex min-h-0 flex-1 overflow-y-auto bg-bg-base px-5 py-10 sm:px-8 sm:py-14">
        <div className="mx-auto flex w-full max-w-4xl flex-col items-center justify-center text-center">
          <div className="mb-7 flex h-12 w-12 items-center justify-center rounded-md bg-accent text-white shadow-sm">
            <Sparkles size={24} />
          </div>
          <p className="mb-3 text-xs font-semibold text-accent">AI 漫画创作工作台</p>
          <h1 className="font-display text-4xl font-semibold text-text-primary sm:text-5xl">
            把故事写成画面
          </h1>
          <p className="mt-4 max-w-xl text-sm leading-7 text-text-secondary sm:text-base">
            从一个人物、一段情节或一句灵感开始，建立你的第一部漫画故事。
          </p>

          <button
            type="button"
            onClick={onCreateStory}
            className="command-surface mt-9 w-full max-w-3xl p-3 text-left transition-transform duration-200 hover:-translate-y-0.5 hover:border-accent/30 sm:p-4"
          >
            <div className="flex items-center justify-between border-b border-border px-1 pb-3 text-xs text-text-muted">
              <span className="flex items-center gap-2 font-medium text-text-secondary"><BookOpenText size={14} /> 新故事</span>
              <span>创建后即可与 AI 对话</span>
            </div>
            <div className="flex min-h-20 items-center gap-3 px-1 pt-3 sm:min-h-24">
              <span className="min-w-0 flex-1 text-sm text-text-muted sm:text-base">写下故事名称，开始构建角色与世界...</span>
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-accent text-white">
                <Send size={17} />
              </span>
            </div>
          </button>

          <div className="mt-5 grid w-full max-w-3xl grid-cols-1 gap-2 sm:grid-cols-3">
            {[
              { icon: <BookOpenText size={16} />, label: '建立故事与角色' },
              { icon: <MessageSquareText size={16} />, label: '对话推进剧情' },
              { icon: <Sparkles size={16} />, label: '生成分镜漫画' },
            ].map((item) => (
              <button
                key={item.label}
                type="button"
                onClick={onCreateStory}
                className="flex min-h-11 items-center justify-center gap-2 rounded-md border border-border bg-bg-raised px-4 text-xs font-medium text-text-secondary transition-colors hover:border-accent/30 hover:text-accent"
              >
                <span className="text-accent">{item.icon}</span>
                {item.label}
              </button>
            ))}
          </div>

          <div className="mt-12 flex w-full max-w-3xl items-center text-left">
            {['故事设定', '章节创作', '漫画生成'].map((label, index) => (
              <div key={label} className="flex min-w-0 flex-1 items-center last:flex-none">
                <div className="flex items-center gap-2">
                  <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-border bg-bg-raised text-[10px] font-semibold text-text-secondary">{index + 1}</span>
                  <span className="hidden text-xs text-text-muted sm:inline">{label}</span>
                </div>
                {index < 2 && <span className="mx-3 h-px min-w-4 flex-1 bg-border sm:mx-5" />}
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      {/* Header */}
      <header className="border-b border-border bg-bg-raised px-4 py-3 sm:px-5">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-md bg-accent text-white">
            <Sparkles size={16} />
          </div>
          <div className="min-w-0">
            <h1 className="font-display text-base font-semibold text-text-primary">创作工坊</h1>
            <p className="text-xs text-text-secondary">AI 漫画创作工坊</p>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        {/* Left sidebar — conversation list only */}
        <aside className="flex h-[132px] w-full min-w-0 shrink-0 flex-col border-b border-border bg-bg-surface/70 p-3 md:h-auto md:w-[250px] md:border-b-0 md:border-r md:p-4">
          <div className="mb-3 flex items-center justify-between">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-text-muted">会话列表</p>
            <button
              onClick={() => void startNewConversation()}
              disabled={!chapterId || conversationLoading}
              className="inline-flex items-center gap-1 rounded-md border border-border bg-bg-base px-2 py-1 text-[11px] text-text-secondary transition hover:border-accent/30 hover:text-accent disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Plus size={12} />
              新建
            </button>
          </div>

          <div className="flex min-h-0 flex-1 gap-2 overflow-x-auto pb-1 md:block md:space-y-1.5 md:overflow-x-hidden md:overflow-y-auto md:pb-0 md:pr-1">
            {conversations.length === 0 ? (
              <div className="w-full rounded-md border border-border bg-bg-raised/70 px-3 py-4 text-center text-xs text-text-muted">
                暂无会话
              </div>
            ) : conversations.map((conversation) => {
              const selected = conversation.conversationId === conversationId;
              const pending = conversation.conversationId === pendingConversationId;
              return (
                <div
                  key={conversation.conversationId}
                  className={`flex min-w-[220px] items-start gap-2 rounded-md border px-3 py-2.5 transition md:min-w-0 ${selected ? 'border-accent/40 bg-accent-muted/30' : 'border-border bg-bg-raised hover:border-accent/30'} ${pending ? 'ring-1 ring-accent/30' : ''}`}
                >
                  <button
                    type="button"
                    onClick={() => void loadSelectedConversation(conversation.conversationId)}
                    className="flex min-w-0 flex-1 items-start gap-2.5 text-left"
                  >
                    <div className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md ${selected ? 'bg-accent text-white' : 'bg-bg-surface text-text-secondary'}`}>
                      {pending ? <Loader2 size={13} className="animate-spin" /> : <MessageSquareText size={13} />}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-1.5">
                        <div className="truncate text-xs font-medium text-text-primary">{conversation.title || '新会话'}</div>
                        {conversation.isActive && <span className="shrink-0 rounded-full border border-success/20 bg-success/10 px-1.5 py-0.5 text-[10px] text-success">进行中</span>}
                        {pending && <span className="shrink-0 rounded-full border border-accent-secondary/20 bg-accent-secondary/10 px-1.5 py-0.5 text-[10px] text-accent-secondary">切换中</span>}
                      </div>
                      <div className="mt-0.5 text-[10px] text-text-muted">
                        {conversationStatusLabel(conversation.status)}
                        {conversation.updatedAt && <span> · {formatTimestamp(conversation.updatedAt)}</span>}
                      </div>
                    </div>
                    <ChevronRight size={12} className="mt-1 shrink-0 text-text-muted" />
                  </button>
                  <button
                    type="button"
                    onClick={() => void deleteConversation(conversation)}
                    className="mt-0.5 rounded p-0.5 text-text-muted transition hover:bg-accent-muted/30 hover:text-accent"
                    title="删除会话"
                  >
                    <Archive size={12} />
                  </button>
                </div>
              );
            })}
          </div>
        </aside>

        {/* Main chat area */}
        <main className="flex min-h-0 min-w-0 flex-1 flex-col bg-bg-raised">
          <div className="flex items-center gap-2.5 border-b border-border px-4 py-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-bg-surface">
              <Bot size={16} className="text-text-secondary" />
            </div>
            <div className="min-w-0">
              <div className="text-sm font-medium text-text-primary">AI 对话</div>
              <div className="text-[11px] text-text-muted">漫画创作助手</div>
            </div>
          </div>

          {error && <div className="mx-4 mt-3 rounded-md border border-accent/20 bg-accent-soft px-3 py-2 text-xs text-accent">{error}</div>}

          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            {bootLoading || conversationLoading ? (
              <div className="flex h-full items-center justify-center">
                <Loader2 size={24} className="animate-spin text-accent" />
              </div>
            ) : messages.length === 0 && !showExecutionPanel ? (
              <div className="flex h-full flex-col items-center justify-center px-6 text-center">
                <div className="mb-5 flex h-16 w-16 items-center justify-center rounded-md border border-border bg-bg-surface">
                  <BookOpenText size={30} className="text-accent/60" />
                </div>
                <h2 className="font-display text-2xl font-semibold text-text-primary">开始创作</h2>
                <p className="mt-2 max-w-md text-sm leading-relaxed text-text-secondary">
                  在下方选择故事和章节，输入创作指令，AI 将协助你推进剧情、生成分镜和漫画。
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {messages.map((msg, idx) => (
                  <div key={`${msg.requestId || 'msg'}-${idx}`} className={msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
                    <div className={'max-w-[85%] rounded-xl px-4 py-2.5 text-sm leading-relaxed ' + (msg.role === 'user' ? 'bg-accent text-white' : msg.role === 'system' ? 'border border-border bg-bg-surface text-text-secondary' : 'border border-border bg-bg-raised text-text-primary shadow-sm')}>
                      {msg.role === 'assistant' || msg.role === 'system' ? <MarkdownRenderer content={msg.content} /> : msg.content}
                    </div>
                  </div>
                ))}

                {showExecutionPanel && (
                  <div className="flex justify-start">
                    <div className="max-w-[90%] min-w-0 rounded-xl border border-border bg-bg-surface px-4 py-3 text-sm">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[11px] font-medium ${executionBadgeClass(latestExecutionEvent?.tone || (waitingForHuman ? 'waiting' : sending ? 'thinking' : 'neutral'))}`}>
                          {executionIcon(latestExecutionEvent?.tone || (waitingForHuman ? 'waiting' : sending ? 'thinking' : 'neutral'), latestExecutionEvent?.icon || 'clock')}
                          {waitingForHuman ? '等待确认' : runStatus}
                        </span>
                        {businessStatus && <span className="rounded-full border border-border bg-bg-base px-2.5 py-0.5 text-[11px] text-text-secondary">状态 {businessStatus}</span>}
                        {activeRoute && <span className="rounded-full border border-border bg-bg-base px-2.5 py-0.5 text-[11px] text-text-secondary">{routeLabel(activeRoute)}智能体{routeConfidence !== null ? ` ${Math.round(routeConfidence * 100)}%` : ''}</span>}
                        {currentPhase && <span className="rounded-full border border-border bg-bg-base px-2.5 py-0.5 text-[11px] text-text-secondary">阶段 {currentPhase === 'TOOL' ? '工具调用' : '模型推理'}</span>}
                        {activeRequestId && <span className="text-[10px] text-text-muted font-mono">{formatRequestId(activeRequestId)}</span>}
                        {activeRequestId && (sending || waitingForHuman) && (
                          <button onClick={() => void cancelActiveRun()} className="inline-flex items-center gap-1 rounded-full border border-accent/30 bg-accent-soft px-2.5 py-0.5 text-[11px] text-accent transition hover:bg-accent-muted/40">
                            <Square size={10} />
                            停止
                          </button>
                        )}
                      </div>
                      {lastProgressAt && <div className="mt-1.5 text-[10px] text-text-muted">最后有效进度：{formatTimestamp(lastProgressAt)}</div>}

                      {executionEvents.length > 0 && (
                        <details className="mt-2">
                          <summary className="cursor-pointer text-xs text-text-muted hover:text-text-secondary transition-colors">查看运行日志 ({executionEvents.length} 条事件)</summary>
                          <div className="mt-2 grid gap-1.5">
                            {executionEvents.slice(-10).map((event) => (
                              <div key={event.id} className="flex items-start gap-2 rounded-md border border-border bg-bg-base/50 px-2.5 py-2 text-xs">
                                <div className="mt-0.5 shrink-0">{executionIcon(event.tone, event.icon)}</div>
                                <div className="min-w-0 flex-1">
                                  <div className="flex flex-wrap items-center gap-1.5">
                                    <span className="font-medium text-text-primary">{event.title}</span>
                                    <span className="text-[10px] uppercase text-text-muted">{event.type}</span>
                                  </div>
                                  <div className="mt-0.5 text-text-secondary">{event.detail}</div>
                                </div>
                                {event.createdAt && <div className="shrink-0 text-[10px] text-text-muted">{formatTimestamp(event.createdAt)}</div>}
                              </div>
                            ))}
                          </div>
                        </details>
                      )}
                    </div>
                  </div>
                )}

                {draftReply && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-xl border border-border bg-bg-raised px-4 py-2.5 text-sm leading-relaxed shadow-sm">
                      <MarkdownRenderer content={draftReply} />
                    </div>
                  </div>
                )}

                {userInputRequest && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-xl border border-accent-secondary/30 bg-accent-secondary/10 px-4 py-3 text-sm">
                      <div className="text-[11px] font-semibold uppercase tracking-wider text-accent-secondary/80">需要确认</div>
                      <div className="mt-1.5 text-sm font-medium text-text-primary">{userInputRequest.question}</div>
                      {userInputRequest.reason && <div className="mt-1 text-xs text-text-secondary">{userInputRequest.reason}</div>}
                      <div className="mt-3 space-y-1.5">
                        {userInputRequest.options.map((option) => (
                          <button key={option.id} onClick={() => void resumeWithAnswer(option.label)} className="w-full rounded-md border border-border bg-bg-base px-3 py-2.5 text-left text-xs transition hover:border-accent/30 hover:bg-accent-muted/10">
                            <div className="flex items-center gap-2 font-medium text-text-primary">
                              <span>{option.label}</span>
                              {option.recommended && <span className="rounded-full bg-accent-secondary/10 px-1.5 py-0.5 text-[10px] text-accent-secondary">推荐</span>}
                            </div>
                            {option.description && <div className="mt-0.5 text-text-secondary">{option.description}</div>}
                          </button>
                        ))}
                      </div>
                      {userInputRequest.allowFreeText && (
                        <div className="mt-2.5 flex gap-2">
                          <input
                            value={customAnswer}
                            onChange={(e) => setCustomAnswer(e.target.value)}
                            placeholder="输入你的回答"
                            className="min-w-0 flex-1 rounded-md border border-border bg-bg-base px-3 py-2 text-xs text-text-primary outline-none transition focus:border-accent"
                          />
                          <button onClick={() => void resumeWithAnswer(customAnswer)} disabled={!customAnswer.trim()} className="rounded-md bg-accent px-3 py-2 text-xs font-medium text-white disabled:cursor-not-allowed disabled:opacity-40 hover:bg-accent-hover transition-colors">
                            继续
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                <div ref={messagesEndRef} />
              </div>
            )}
          </div>

          <div className="border-t border-border px-4 py-3">
            {/* Story / Chapter / Model row */}
            <div className="mb-3 flex flex-wrap items-center gap-2">
              {/* Story selector — opens upward */}
              <SelectUpward
                value={storyId}
                label="暂无故事"
                width="w-[120px]"
                options={stories.length === 0 ? [{ value: '', label: '暂无故事' }] : stories.map(s => ({ value: String(s.id), label: s.title }))}
                onChange={(v) => setStoryId(v)}
              />

              {/* Chapter selector — opens upward */}
              <SelectUpward
                value={chapterId}
                label={chapterLoading ? '加载中…' : '暂无章节'}
                width="w-[100px]"
                disabled={chapterLoading || chapters.length === 0}
                options={chapters.length === 0 ? [{ value: '', label: '暂无章节' }] : chapters.map(c => ({ value: String(c.id), label: `第${c.chapter_number} 章` }))}
                onChange={(v) => setChapterId(v)}
              />

              {/* Divider */}
              <span className="mx-0.5 h-5 w-px shrink-0 bg-border" />

              {/* Spacer — pushes model switcher to the right */}
              <div className="flex-1" />

              <ModelSwitcher
                capability="llm"
                selectedModel={selectedLlmModel}
                onSelect={setSelectedLlmModel}
                disabled={sending || conversationLoading}
              />
            </div>
            <div className="flex gap-2.5">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void startRun();
                  }
                }}
                rows={2}
                placeholder={chapterId ? '输入创作指令，例如：检查这一章能否直接转成分镜？' : '请先选择故事和章节'}
                className="min-h-[52px] flex-1 resize-none rounded-lg border border-border bg-bg-surface px-3.5 py-2.5 text-sm text-text-primary outline-none transition placeholder:text-text-muted focus:border-accent"
              />
              <button
                onClick={() => void startRun()}
                disabled={sending || conversationLoading || !chapterId || !input.trim()}
                className="inline-flex h-auto min-w-12 items-center justify-center gap-1.5 rounded-md bg-accent px-3 py-2.5 text-sm font-medium text-white transition hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40 sm:min-w-[100px] sm:px-4"
              >
                {sending ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
                <span className="hidden sm:inline">发送</span>
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
