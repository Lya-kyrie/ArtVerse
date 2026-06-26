import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
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
import {
  cancelMangaAgentConversationRun,
  createMangaAgentConversation,
  deleteMangaAgentConversation,
  getMangaAgentConversationMessages,
  getOpenMangaAgentConversationRun,
  listChapters,
  listMangaAgentConversations,
  listStories,
  resumeMangaAgentAgUiStream,
  runMangaAgentAgUiStream,
  type AgentUserInputRequest,
  type Chapter,
  type MangaAgentConversation,
  type MangaAgentMessage,
  type MangaAgentRunSnapshot,
  type MangaWorkflowRoute,
  type Story,
} from '../api';

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

const WORKFLOW_ROUTES: Array<{ value: MangaWorkflowRoute; label: string; description: string }> = [
  { value: 'AUTO', label: '自动', description: '先识别用户意图，再进入合适的任务模式' },
  { value: 'DIRECTOR', label: '导演', description: '规划章节、生成或修订分镜' },
  { value: 'REVIEW', label: '质检', description: '检查现有分镜和下一步风险' },
  { value: 'HITL', label: '决策', description: '收束需要用户确认的选择' },
  { value: 'CHAT', label: '聊天', description: '只回答问题，不修改章节内容' },
];

function routeLabel(route: MangaWorkflowRoute | string | undefined): string {
  return WORKFLOW_ROUTES.find((item) => item.value === route)?.label || '自动';
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

function renderInlineMarkdown(text: string): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return parts.map((part, index) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={index} className="font-semibold text-white">{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={index} className="rounded bg-white/10 px-1 py-0.5 text-[0.9em] text-amber-100">{part.slice(1, -1)}</code>;
    }
    return <Fragment key={index}>{part}</Fragment>;
  });
}

function MarkdownMessage({ content }: { content: string }) {
  return <div className="whitespace-pre-wrap text-sm leading-7 text-gray-200">{renderInlineMarkdown(content)}</div>;
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
      detail: route ? `正在执行 ${routeLabel(route as MangaWorkflowRoute)} 模式：${message}` : message,
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
    if (name === 'intent_classified' || value.type === 'intent_classified') {
      const selectedRoute = String(data.selectedRoute || value.selectedRoute || value.route || '');
      const rawConfidence = typeof data.confidence === 'number' ? data.confidence : value.confidence;
      const confidence = typeof rawConfidence === 'number' ? Math.round(rawConfidence * 100) : null;
      const reason = String(data.reason || value.reason || '');
      const requiresConfirmation = Boolean(data.requiresConfirmation ?? value.requiresConfirmation);
      return {
        id: `${type}-${name}-${createdAt || Date.now()}`,
        type,
        title: '用户意图识别完成',
        detail: `识别为 ${routeLabel(selectedRoute)} 模式${confidence == null ? '' : ` · 置信度 ${confidence}%`}${reason ? ` · ${reason}` : ''}`,
        createdAt,
        tone: requiresConfirmation ? 'waiting' : 'thinking',
        icon: requiresConfirmation ? 'question' : 'sparkles',
      };
    }
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
    info: 'border-white/10 bg-white/[0.04] text-gray-300',
    neutral: 'border-white/10 bg-white/[0.04] text-gray-300',
    thinking: 'border-amber-300/20 bg-amber-300/[0.08] text-amber-100',
    tool: 'border-cyan-300/20 bg-cyan-300/[0.08] text-cyan-100',
    waiting: 'border-violet-300/20 bg-violet-300/[0.08] text-violet-100',
    success: 'border-emerald-300/20 bg-emerald-300/[0.08] text-emerald-100',
    warning: 'border-orange-300/20 bg-orange-300/[0.08] text-orange-100',
    error: 'border-red-300/20 bg-red-300/[0.08] text-red-100',
  }[tone];
}

function executionIcon(tone: AgUiEventTone, icon: ExecutionEventItem['icon']) {
  const className = {
    info: 'text-gray-300',
    neutral: 'text-gray-300',
    thinking: 'text-amber-200',
    tool: 'text-cyan-200',
    waiting: 'text-violet-200',
    success: 'text-emerald-200',
    warning: 'text-orange-200',
    error: 'text-red-200',
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

export default function MangaAgentPage() {
  const [stories, setStories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [storyId, setStoryId] = useState('');
  const [chapterId, setChapterId] = useState('');
  const [conversations, setConversations] = useState<ConversationView[]>([]);
  const [conversationId, setConversationId] = useState('');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [bootLoading, setBootLoading] = useState(true);
  const [chapterLoading, setChapterLoading] = useState(false);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [workflowRoute, setWorkflowRoute] = useState<MangaWorkflowRoute>('AUTO');
  const [userInputRequest, setUserInputRequest] = useState<AgentUserInputRequest | null>(null);
  const [customAnswer, setCustomAnswer] = useState('');
  const [draftReply, setDraftReply] = useState('');
  const [runStatus, setRunStatus] = useState('尚未开始运行');
  const [businessStatus, setBusinessStatus] = useState('');
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

  const activeStory = useMemo(() => stories.find((story) => String(story.id) === storyId) ?? null, [stories, storyId]);
  const activeChapter = useMemo(() => chapters.find((chapter) => String(chapter.id) === chapterId) ?? null, [chapters, chapterId]);
  const activeConversation = useMemo(() => conversations.find((conversation) => conversation.conversationId === conversationId) ?? null, [conversations, conversationId]);
  const pendingConversation = useMemo(() => conversations.find((conversation) => conversation.conversationId === pendingConversationId) ?? null, [conversations, pendingConversationId]);
  const latestExecutionEvent = executionEvents.length > 0 ? executionEvents[executionEvents.length - 1] : null;
  const showExecutionPanel = executionEvents.length > 0 || !!userInputRequest || !!draftReply || !!activeRequestId || sending;
  const waitingForHuman = !!userInputRequest;

  useEffect(() => { chapterIdRef.current = chapterId; }, [chapterId]);
  useEffect(() => { conversationIdRef.current = conversationId; }, [conversationId]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages, executionEvents, draftReply, userInputRequest, sending]);
  useEffect(() => () => { activeStreamControllerRef.current?.abort(); }, []);

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
        setActiveRequestId(null);
        setRunStatus('会话已就绪');
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
    if (snapshot.route) setWorkflowRoute(snapshot.route);
    setRunStatus(snapshot.status === 'WAITING_USER' ? '等待用户确认' : `业务状态：${snapshot.status}`);
    setBusinessStatus(snapshot.status);
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

  function resetLiveState() {
    setInput('');
    setUserInputRequest(null);
    setCustomAnswer('');
    setDraftReply('');
    setExecutionEvents([]);
    setRunStatus('尚未开始运行');
    setBusinessStatus('');
    setActiveRequestId(null);
    activeRunConversationIdRef.current = '';
  }

  function recordAgUiEvent(rawEvent: Record<string, any>) {
    const executionEvent = inferExecutionEvent(rawEvent);
    setExecutionEvents((prev) => appendExecutionEvent(prev, executionEvent));

    if (rawEvent.type === 'STATE_SNAPSHOT') {
      const snapshot = asRecord(rawEvent.snapshot);
      const status = String(snapshot.status || rawEvent.status || '');
      const message = String(snapshot.message || rawEvent.message || '');
      if (status) setBusinessStatus(status);
      if (message) setRunStatus(message);
      else if (status) setRunStatus(`业务状态：${status}`);
      if (snapshot.route) setWorkflowRoute(snapshot.route as MangaWorkflowRoute);
    }

    if (rawEvent.type === 'RUN_STARTED') {
      const message = String(rawEvent.input?.state?.message || rawEvent.input?.message || '智能体已启动');
      setRunStatus(message);
      setBusinessStatus('RUNNING');
      if (rawEvent.route) setWorkflowRoute(rawEvent.route as MangaWorkflowRoute);
    }

    if (rawEvent.type === 'CUSTOM') {
      const value = asRecord(rawEvent.value);
      const data = asRecord(value.data);
      const status = String(value.status || '');
      if (status) setBusinessStatus(status);
      if (value.message) setRunStatus(String(value.message));
      const selectedRoute = data.selectedRoute || value.selectedRoute;
      if (rawEvent.name === 'intent_classified' && selectedRoute) {
        setWorkflowRoute(selectedRoute as MangaWorkflowRoute);
        setRunStatus(`已识别为${routeLabel(String(selectedRoute))}模式`);
      }
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
      recordAgUiEvent({
        type: 'RUN_ERROR',
        message: event.data?.detail || event.data?.error || '智能体请求失败',
      });
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
      workflowRoute,
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

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-[linear-gradient(180deg,_#09090b_0%,_#111827_45%,_#09090b_100%)] text-gray-100">
      <header className="border-b border-white/10 bg-black/15 px-5 py-4 backdrop-blur-sm">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-white/10 bg-white/5">
            <Sparkles size={18} className="text-amber-300" />
          </div>
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-white">漫画智能体</h1>
            <p className="text-sm text-gray-400">会话历史、AG-UI 事件和业务状态统一展示</p>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 gap-4 p-4">
        <aside className="flex w-[340px] min-w-0 shrink-0 flex-col gap-4 rounded-3xl border border-white/10 bg-black/20 p-4 backdrop-blur-sm">
          <div className="space-y-4">
            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">故事</p>
              <select
                value={storyId}
                onChange={(e) => setStoryId(e.target.value)}
                className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50"
              >
                {stories.length === 0 ? <option value="">暂无故事</option> : null}
                {stories.map((story) => (
                  <option key={story.id} value={story.id} className="bg-gray-900 text-gray-100">{story.title}</option>
                ))}
              </select>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">章节</p>
              <div className="relative">
                <select
                  value={chapterId}
                  onChange={(e) => setChapterId(e.target.value)}
                  disabled={chapterLoading || chapters.length === 0}
                  className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50 disabled:opacity-40"
                >
                {chapters.length === 0 ? <option value="">暂无章节</option> : null}
                {chapters.map((chapter) => (
                  <option key={chapter.id} value={chapter.id} className="bg-gray-900 text-gray-100">
                    第{chapter.chapter_number} 章
                  </option>
                ))}
              </select>
                {chapterLoading && <Loader2 size={16} className="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-gray-500" />}
              </div>
            </div>

            <div className="rounded-3xl border border-amber-300/10 bg-amber-300/[0.06] p-4">
              <p className="text-xs uppercase tracking-[0.22em] text-amber-200/70">当前工作区</p>
              <div className="mt-3 space-y-2">
                <div>
                  <div className="text-xs text-gray-500">故事标题</div>
                  <div className="text-sm text-gray-100">{activeStory?.title || '未选择故事'}</div>
                </div>
                <div>
                  <div className="text-xs text-gray-500">章节</div>
                  <div className="text-sm text-gray-100">{activeChapter ? `第${activeChapter.chapter_number} 章` : '未选择章节'}</div>
                </div>
              </div>
              <button
                onClick={() => void startNewConversation()}
                disabled={!chapterId || conversationLoading}
                className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-amber-200/20 bg-black/20 px-3 py-2 text-sm text-amber-100 transition hover:border-amber-200/40 hover:bg-amber-200/10 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <Plus size={15} />
                新建会话
              </button>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">会话列表</p>
              <div className="max-h-[260px] space-y-2 overflow-y-auto pr-1">
                {conversations.length === 0 ? (
                  <div className="rounded-2xl border border-white/10 bg-white/[0.03] px-3 py-4 text-sm text-gray-500">
                    暂无会话
                  </div>
                ) : conversations.map((conversation) => {
                  const selected = conversation.conversationId === conversationId;
                  const pending = conversation.conversationId === pendingConversationId;
                  return (
                    <div
                      key={conversation.conversationId}
                      className={`flex items-start gap-2 rounded-2xl border px-3 py-3 transition ${selected ? 'border-amber-300/50 bg-amber-300/10' : 'border-white/10 bg-white/[0.04] hover:border-white/20 hover:bg-white/[0.07]'} ${pending ? 'ring-1 ring-amber-300/40' : ''}`}
                    >
                      <button
                        type="button"
                        onClick={() => void loadSelectedConversation(conversation.conversationId)}
                        className="flex min-w-0 flex-1 items-start gap-3 text-left"
                      >
                        <div className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl ${selected ? 'bg-amber-300 text-gray-950' : 'bg-white/5 text-gray-300'}`}>
                            {pending ? <Loader2 size={15} className="animate-spin" /> : <MessageSquareText size={15} />}
                        </div>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <div className="truncate text-sm font-medium text-white">{conversation.title || '新会话'}</div>
                            {conversation.isActive && <span className="rounded-full border border-emerald-300/20 bg-emerald-300/10 px-2 py-0.5 text-[11px] text-emerald-100">活动</span>}
                            {pending && <span className="rounded-full border border-amber-300/20 bg-amber-300/10 px-2 py-0.5 text-[11px] text-amber-100">切换中</span>}
                          </div>
                          <div className="mt-1 flex items-center gap-2 text-xs text-gray-500">
                            <span>{conversationStatusLabel(conversation.status)}</span>
                            {conversation.updatedAt && <span>{formatTimestamp(conversation.updatedAt)}</span>}
                          </div>
                        </div>
                        <ChevronRight size={14} className="mt-1 shrink-0 text-gray-500" />
                      </button>
                      <button
                        type="button"
                        onClick={() => void deleteConversation(conversation)}
                        className="mt-0.5 rounded-lg p-1 text-gray-500 transition hover:bg-red-500/10 hover:text-red-200"
                        title="删除会话"
                      >
                        <Archive size={14} />
                      </button>
                    </div>
                  );
                })}
              </div>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">模式</p>
              <div className="grid grid-cols-4 gap-2">
                {WORKFLOW_ROUTES.map((route) => {
                  const selected = workflowRoute === route.value;
                  return (
                    <button
                      key={route.value}
                      type="button"
                      onClick={() => setWorkflowRoute(route.value)}
                      disabled={sending || waitingForHuman}
                      title={route.description}
                      className={`rounded-2xl border px-3 py-2 text-sm transition disabled:cursor-not-allowed disabled:opacity-50 ${selected ? 'border-amber-300/60 bg-amber-300 text-gray-950' : 'border-white/10 bg-white/[0.04] text-gray-300 hover:border-amber-300/30 hover:bg-white/[0.08] hover:text-white'}`}
                    >
                      {route.label}
                    </button>
                  );
                })}
              </div>
              <div className="mt-2 text-xs leading-5 text-gray-500">
                {WORKFLOW_ROUTES.find((route) => route.value === workflowRoute)?.description}
              </div>
            </div>
          </div>
        </aside>

        <main className="flex min-h-0 min-w-0 flex-1 flex-col rounded-[32px] border border-white/10 bg-black/20 backdrop-blur-sm">
          <div className="flex items-center gap-3 border-b border-white/10 px-5 py-4">
            <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-white/5">
              <Bot size={18} className="text-gray-200" />
            </div>
            <div className="min-w-0">
              <div className="text-sm font-medium text-white">漫画智能体</div>
              <div className="text-xs text-gray-500">切换会话后自动恢复该会话的最后一条消息和运行状态</div>
            </div>
          </div>

          {error && <div className="mx-4 mt-4 rounded-2xl border border-red-500/30 bg-red-950/40 px-3 py-2 text-sm text-red-200">{error}</div>}

          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            {bootLoading || conversationLoading ? (
              <div className="flex h-full items-center justify-center">
                <Loader2 size={28} className="animate-spin text-amber-300" />
              </div>
            ) : messages.length === 0 && !showExecutionPanel ? (
              <div className="flex h-full flex-col items-center justify-center px-6 text-center">
                <div className="mb-6 flex h-24 w-24 items-center justify-center rounded-full border border-white/10 bg-white/[0.04]">
                  <BookOpenText size={34} className="text-amber-200" />
                </div>
                <h2 className="text-3xl font-semibold text-white">漫画智能体</h2>
                <p className="mt-3 max-w-xl text-sm leading-7 text-gray-400">
                  先选择故事、章节和会话，再开始运行。右侧会实时显示 AG-UI 事件、业务状态和智能体文本流。
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {messages.map((msg, idx) => (
                  <div key={`${msg.requestId || 'msg'}-${idx}`} className={msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
                    <div className={'max-w-[85%] rounded-3xl px-4 py-3 shadow-sm ' + (msg.role === 'user' ? 'whitespace-pre-wrap bg-amber-300 text-sm leading-7 text-gray-950' : msg.role === 'system' ? 'border border-white/10 bg-white/[0.04] text-gray-300' : 'border border-white/10 bg-white/[0.04] text-gray-200')}>
                      {msg.role === 'assistant' || msg.role === 'system' ? <MarkdownMessage content={msg.content} /> : msg.content}
                    </div>
                  </div>
                ))}

                {showExecutionPanel && (
                  <div className="flex justify-start">
                    <div className="max-w-[90%] rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-4 text-sm text-gray-300">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${executionBadgeClass(latestExecutionEvent?.tone || (waitingForHuman ? 'waiting' : sending ? 'thinking' : 'neutral'))}`}>
                          {executionIcon(latestExecutionEvent?.tone || (waitingForHuman ? 'waiting' : sending ? 'thinking' : 'neutral'), latestExecutionEvent?.icon || 'clock')}
                          {waitingForHuman ? '等待用户输入' : runStatus}
                        </span>
                        <span className="inline-flex items-center rounded-full border border-white/10 bg-black/20 px-3 py-1 text-xs text-gray-300">模式 {routeLabel(workflowRoute)}</span>
                        {businessStatus && <span className="inline-flex items-center rounded-full border border-white/10 bg-black/20 px-3 py-1 text-xs text-gray-300">业务状态 {businessStatus}</span>}
                        {activeRequestId && <span className="inline-flex items-center rounded-full border border-white/10 bg-black/20 px-3 py-1 text-xs text-gray-300">requestId {formatRequestId(activeRequestId)}</span>}
                        {latestExecutionEvent?.createdAt && <span className="text-xs text-gray-500">{formatTimestamp(latestExecutionEvent.createdAt)}</span>}
                        {activeRequestId && (sending || waitingForHuman) && (
                          <button onClick={() => void cancelActiveRun()} className="inline-flex items-center gap-1 rounded-full border border-red-400/30 bg-red-950/30 px-3 py-1 text-xs text-red-100 transition hover:border-red-300/60 hover:bg-red-900/40">
                            <Square size={12} />
                            停止
                          </button>
                        )}
                      </div>

                      <div className="mt-3 rounded-2xl border border-white/10 bg-black/15 px-4 py-3">
                        <div className="flex items-start gap-3">
                          <div className="mt-0.5">{executionIcon(latestExecutionEvent?.tone || 'neutral', latestExecutionEvent?.icon || 'clock')}</div>
                          <div className="min-w-0 flex-1">
                            <div className="text-sm font-medium text-white">{latestExecutionEvent?.title || '运行面板'}</div>
                            <div className="mt-1 text-xs leading-5 text-gray-400">{latestExecutionEvent?.detail || '正在等待 AG-UI 事件驱动状态更新'}</div>
                          </div>
                        </div>
                      </div>

                      {executionEvents.length > 0 && (
                        <div className="mt-3 grid gap-2">
                          {executionEvents.slice(-10).map((event) => (
                            <div key={event.id} className="flex items-start gap-3 rounded-2xl border border-white/10 bg-black/15 px-3 py-3">
                              <div className="mt-0.5">{executionIcon(event.tone, event.icon)}</div>
                              <div className="min-w-0 flex-1">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="text-sm font-medium text-white">{event.title}</span>
                                  <span className="text-[11px] uppercase tracking-[0.18em] text-gray-500">{event.type}</span>
                                </div>
                                <div className="mt-1 text-xs leading-5 text-gray-400">{event.detail}</div>
                              </div>
                              {event.createdAt && <div className="shrink-0 text-[11px] text-gray-500">{formatTimestamp(event.createdAt)}</div>}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {draftReply && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm leading-7 text-gray-200">
                      <MarkdownMessage content={draftReply} />
                    </div>
                  </div>
                )}

                {userInputRequest && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-3xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-4 text-sm text-gray-100">
                      <div className="text-xs uppercase tracking-[0.18em] text-amber-200/70">人工介入</div>
                      <div className="mt-2 text-base font-medium text-white">{userInputRequest.question}</div>
                      {userInputRequest.reason && <div className="mt-1 text-xs text-gray-400">{userInputRequest.reason}</div>}
                      <div className="mt-4 space-y-2">
                        {userInputRequest.options.map((option) => (
                          <button key={option.id} onClick={() => void resumeWithAnswer(option.label)} className="w-full rounded-2xl border border-white/10 bg-black/20 px-3 py-3 text-left transition hover:border-amber-300/40 hover:bg-white/[0.06]">
                            <div className="flex items-center gap-2 text-sm font-medium text-white">
                              <span>{option.label}</span>
                              {option.recommended && <span className="rounded-full bg-amber-300/15 px-2 py-0.5 text-[11px] text-amber-100">推荐</span>}
                            </div>
                            {option.description && <div className="mt-1 text-xs leading-5 text-gray-400">{option.description}</div>}
                          </button>
                        ))}
                      </div>
                      {userInputRequest.allowFreeText && (
                        <div className="mt-3 flex gap-2">
                          <input
                            value={customAnswer}
                            onChange={(e) => setCustomAnswer(e.target.value)}
                            placeholder="输入其他答案"
                            className="min-w-0 flex-1 rounded-2xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-gray-100 outline-none focus:border-amber-300/40"
                          />
                          <button onClick={() => void resumeWithAnswer(customAnswer)} disabled={!customAnswer.trim()} className="rounded-2xl bg-amber-300 px-4 py-2 text-sm font-medium text-gray-950 disabled:cursor-not-allowed disabled:opacity-40">
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

          <div className="border-t border-white/10 p-4">
            <div className="flex gap-3">
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
                placeholder={chapterId ? '例如：检查这一章能否直接转成分镜？' : '请先选择故事和章节'}
                className="min-h-[58px] flex-1 resize-none rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-gray-100 outline-none transition placeholder:text-gray-500 focus:border-amber-300/40"
              />
              <button
                onClick={() => void startRun()}
                disabled={sending || conversationLoading || !chapterId || !input.trim()}
                className="inline-flex h-auto min-w-[110px] items-center justify-center gap-2 rounded-3xl bg-amber-300 px-4 py-3 text-sm font-medium text-gray-950 transition hover:bg-amber-200 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {sending ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                发送
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
