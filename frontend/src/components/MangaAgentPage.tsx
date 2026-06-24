import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Bot,
  BookOpenText,
  CheckCircle2,
  Clock3,
  Loader2,
  MessageCircleQuestion,
  Plus,
  Send,
  Sparkles,
  Square,
  TriangleAlert,
  Wrench,
} from 'lucide-react';
import {
  type AgentRunTimelineEvent,
  type AgentUserInputRequest,
  cancelMangaAgentConversationRun,
  createMangaAgentConversation,
  getMangaAgentConversationMessages,
  getMangaAgentConversationRunState,
  getMangaAgentMessages,
  getOpenMangaAgentConversationRun,
  listChapters,
  listMangaAgentConversations,
  listStories,
  resumeMangaAgentAgUiStream,
  runMangaAgentAgUiStream,
  type MangaWorkflowRoute,
  type Chapter,
  type MangaAgentMessage,
  type MangaAgentRunEvent,
  type MangaAgentRunSnapshot,
  type Story,
} from '../api';

interface Message {
  role: 'user' | 'assistant' | 'system';
  content: string;
  requestId?: string;
}

type ExecutionTone = 'neutral' | 'thinking' | 'tool' | 'waiting' | 'success' | 'warning' | 'error';

const STARTER_PROMPTS = [
  'Check the progress of this chapter and tell me the next step.',
  'Based on the current content, generate the storyboard for this chapter first.',
  'Review whether the storyboard still needs refinement.',
];

const ROUTE_OPTIONS: Array<{ value: MangaWorkflowRoute; label: string; description: string }> = [
  { value: 'DIRECTOR', label: '导演', description: '主流程，适合常规章节推进与分镜生成。' },
  { value: 'HITL', label: '人工确认', description: '需要用户先做选择或确认时使用。' },
  { value: 'REVIEW', label: '复审', description: '用于检查已有分镜内容是否需要调整。' },
];

function requestIdOf(value: { requestId?: string; request_id?: string } | null | undefined) {
  return value?.requestId ?? value?.request_id ?? '';
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

function toMessages(items: MangaAgentMessage[]): Message[] {
  return items.flatMap((item) => {
    if (item.role !== 'user' && item.role !== 'assistant' && item.role !== 'system') return [];
    const content = item.role === 'system' ? item.content : item.content;
    if (!content) return [];
    return [{
      role: item.role,
      content,
      requestId: item.requestId ?? item.request_id,
    }];
  });
}

function timelineEventSummary(event: AgentRunTimelineEvent): {
  tone: ExecutionTone;
  title: string;
  detail: string;
  icon: 'bot' | 'sparkles' | 'wrench' | 'question' | 'check' | 'warning' | 'clock';
} {
  const data = event.data || {};
  const type = String(data.type || event.type);
  const label = String(data.label || data.phase || data.toolName || event.label || event.type);

  if (event.type === 'status' || type === 'ag_ui_state_snapshot') {
    return {
      tone: 'neutral',
      title: event.label || '运行状态',
      detail: String(event.text || data.message || '智能体正在处理任务'),
      icon: 'clock',
    };
  }
  if (event.type === 'tool' || type === 'ag_ui_tool_call_start' || type === 'ag_ui_tool_call_end' || type === 'ag_ui_tool_call_result') {
    const tool = String(data.tool || event.toolName || 'tool');
    const suffix = data.error ? ` / ${String(data.error)}` : `${data.saved === true ? 'saved' : ''}${typeof data.scenes_count === 'number' ? ` / ${data.scenes_count} scenes` : ''}`;
    return {
      tone: data.succeeded === false ? 'error' : 'tool',
      title: tool,
      detail: data.succeeded === false ? `工具调用失败${suffix ? `: ${suffix}` : ''}` : `工具调用完成${suffix}`,
      icon: data.succeeded === false ? 'warning' : 'wrench',
    };
  }
  if (type === 'text_delta') {
    return {
      tone: 'neutral',
      title: 'Generating reply',
      detail: String(event.text || data.text || '智能体正在整理最终回复'),
      icon: 'bot',
    };
  }
  if (type === 'run_started' || type === 'ag_ui_run_started') {
    return { tone: 'thinking', title: label || '智能体已启动', detail: '正在分析当前章节上下文', icon: 'bot' };
  }
  if (type === 'context_loading') {
    return { tone: 'thinking', title: label || '同步章节知识', detail: '正在将故事知识写入工作区', icon: 'sparkles' };
  }
  if (type === 'model_started' || type === 'thinking_started' || type === 'ag_ui_step_started') {
    return { tone: 'thinking', title: label || '模型推理中', detail: '模型正在推理下一步动作', icon: 'sparkles' };
  }
  if (type === 'tool_call_started' || type === 'tool_started' || type === 'tool_call_ready') {
    return { tone: 'tool', title: label || '工具执行中', detail: '智能体正在调用工具', icon: 'wrench' };
  }
  if (type === 'tool_finished') {
    return { tone: 'tool', title: label || '工具执行完成', detail: '工具执行完成，正在整理结果', icon: 'wrench' };
  }
  if (type === 'user_answered') {
    return { tone: 'waiting', title: '已收到用户选择', detail: String(data.answer || '继续使用默认方案'), icon: 'question' };
  }
  if (type === 'reply_ready') {
    return { tone: 'success', title: label || '最终回复已生成', detail: '智能体已开始输出最终结果', icon: 'check' };
  }
  if (type === 'run_finished' || type === 'ag_ui_run_finished') {
    return { tone: 'success', title: label || '任务已完成', detail: '本次运行已结束', icon: 'check' };
  }
  if (type === 'user_input_requested' || type === 'ag_ui_run_interrupted') {
    const options = Array.isArray(data.options) ? data.options : [];
    return {
      tone: 'waiting',
      title: String(data.question || '需要你做出选择'),
      detail: String(data.reason || `可选项：${options.length}`),
      icon: 'question',
    };
  }
  if (event.type === 'error' || type === 'ag_ui_run_error') {
    return {
      tone: 'error',
      title: '运行失败',
      detail: String(data.detail || data.error || data.message || '智能体请求失败'),
      icon: 'warning',
    };
  }
  return { tone: 'neutral', title: label || event.type, detail: '已收到事件', icon: 'clock' };
}

function executionBadgeClass(tone: ExecutionTone): string {
  return {
    neutral: 'border-white/10 bg-white/[0.04] text-gray-300',
    thinking: 'border-amber-300/20 bg-amber-300/[0.08] text-amber-100',
    tool: 'border-cyan-300/20 bg-cyan-300/[0.08] text-cyan-100',
    waiting: 'border-violet-300/20 bg-violet-300/[0.08] text-violet-100',
    success: 'border-emerald-300/20 bg-emerald-300/[0.08] text-emerald-100',
    warning: 'border-orange-300/20 bg-orange-300/[0.08] text-orange-100',
    error: 'border-red-300/20 bg-red-300/[0.08] text-red-100',
  }[tone];
}

function executionIcon(tone: ExecutionTone, icon: 'bot' | 'sparkles' | 'wrench' | 'question' | 'check' | 'warning' | 'clock') {
  const className = {
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
    case 'clock':
    default:
      return <Clock3 size={size} className={className} />;
  }
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

function appendRunEvent(events: AgentRunTimelineEvent[], event: AgentRunTimelineEvent): AgentRunTimelineEvent[] {
  if (event.type === 'text_delta') return events;
  const last = events[events.length - 1];
  if (last && last.type === event.type && last.label === event.label && last.status === event.status) {
    return events;
  }
  return [...events, event].slice(-24);
}

function normalizeConversationId(value: unknown): string {
  return typeof value === 'string' && value !== 'undefined' && value.trim() ? value : '';
}

export default function MangaAgentPage() {
  const [stories, setStories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [storyId, setStoryId] = useState('');
  const [chapterId, setChapterId] = useState('');
  const [conversationId, setConversationId] = useState('');
  const [route, setRoute] = useState<MangaWorkflowRoute>('DIRECTOR');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [bootLoading, setBootLoading] = useState(true);
  const [chapterLoading, setChapterLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState('');
  const [runStatus, setRunStatus] = useState('正在思考当前章节...');
  const [runEvents, setRunEvents] = useState<AgentRunTimelineEvent[]>([]);
  const [draftReply, setDraftReply] = useState('');
  const [userInputRequest, setUserInputRequest] = useState<AgentUserInputRequest | null>(null);
  const [customAnswer, setCustomAnswer] = useState('');
  const chapterIdRef = useRef('');
  const conversationIdRef = useRef('');
  const activeRequestIdRef = useRef<string | null>(null);
  const runPollTimerRef = useRef<number | undefined>(undefined);
  const activeStreamControllerRef = useRef<AbortController | null>(null);
  const draftReplyRef = useRef('');
  const messagesEndRef = useRef<HTMLDivElement | null>(null);

  const latestRunEvent = runEvents.length > 0 ? runEvents[runEvents.length - 1] : null;
  const latestRunSummary = latestRunEvent ? timelineEventSummary(latestRunEvent) : null;
  const showExecutionPanel = loading || runEvents.length > 0 || !!userInputRequest || !!draftReply;
  const visibleRequestId = userInputRequest?.requestId ?? activeRequestIdRef.current ?? '';

  const activeStory = useMemo(() => stories.find((story) => String(story.id) === storyId) ?? null, [stories, storyId]);
  const activeChapter = useMemo(() => chapters.find((chapter) => String(chapter.id) === chapterId) ?? null, [chapters, chapterId]);
  const activeRoute = useMemo(() => ROUTE_OPTIONS.find((item) => item.value === route) ?? ROUTE_OPTIONS[0], [route]);
  const emptyState = messages.length === 0 && !historyLoading;

  useEffect(() => { chapterIdRef.current = chapterId; }, [chapterId]);
  useEffect(() => { conversationIdRef.current = conversationId; }, [conversationId]);
  useEffect(() => { draftReplyRef.current = draftReply; }, [draftReply]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages, runEvents, draftReply, loading, userInputRequest]);
  useEffect(() => () => {
    if (runPollTimerRef.current !== undefined) window.clearTimeout(runPollTimerRef.current);
    activeStreamControllerRef.current?.abort();
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
        if (active) setError(err.message || '加载故事失败');
      } finally {
        if (active) setBootLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let active = true;
    const load = async () => {
      if (!storyId) {
        setChapters([]);
        setChapterId('');
        return;
      }
      setChapterLoading(true);
      setError('');
      try {
        const list = await listChapters(Number(storyId));
        if (!active) return;
        setChapters(list);
        setChapterId((prev) => (prev && list.some((chapter) => String(chapter.id) === prev) ? prev : (list[0] ? String(list[0].id) : '')));
      } catch (err: any) {
        if (active) setError(err.message || '加载章节失败');
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
      setInput('');
      setError('');
      setLoading(false);
      setMessages([]);
      setRunEvents([]);
      setDraftReply('');
      setUserInputRequest(null);
      setCustomAnswer('');
      setConversationId('');
      setRoute('DIRECTOR');
      activeRequestIdRef.current = null;
      if (runPollTimerRef.current !== undefined) {
        window.clearTimeout(runPollTimerRef.current);
        runPollTimerRef.current = undefined;
      }
      if (!chapterId) return;
      setHistoryLoading(true);
      try {
        const id = Number(chapterId);
        const conversations = await listMangaAgentConversations(id);
        const activeConversation = conversations.find((item) => item.status === 'ACTIVE') || await createMangaAgentConversation(id);
        if (!active) return;
        const resolvedConversationId = normalizeConversationId((activeConversation as any)?.conversationId ?? (activeConversation as any)?.conversation_id);
        if (!resolvedConversationId) {
          throw new Error('会话标识缺失，请刷新页面后重试');
        }
        setConversationId(resolvedConversationId);
        const list = await getMangaAgentConversationMessages(id, resolvedConversationId);
        if (!active) return;
        setMessages(toMessages(list));
        const openRun = await getOpenMangaAgentConversationRun(id, resolvedConversationId);
        if (!active || !openRun) return;
        restoreRunSnapshot(openRun);
      } catch (err: any) {
        if (active) setError(err.message || '加载对话历史失败');
      } finally {
        if (active) setHistoryLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [chapterId]);

  function restoreRunSnapshot(snapshot: MangaAgentRunSnapshot) {
    activeRequestIdRef.current = snapshot.requestId ?? snapshot.request_id ?? null;
    setRunStatus(snapshot.status);
    if (snapshot.route) setRoute(snapshot.route);
    setUserInputRequest(snapshot.userInputRequest ?? null);
    setRunEvents((snapshot.events || []).map((event) => (event.data as AgentRunTimelineEvent) || {
      type: String((event as any).eventName || 'event'),
      data: (event as any).data || {},
      createdAt: event.createdAt,
    }));
    if (snapshot.finalReply) setDraftReply(snapshot.finalReply);
  }

  function clearRunPoll() {
    if (runPollTimerRef.current !== undefined) {
      window.clearTimeout(runPollTimerRef.current);
      runPollTimerRef.current = undefined;
    }
  }

  async function reloadMessages(id: number, requestChapterId: string, requestConversationId: string) {
    if (!requestConversationId || requestConversationId === 'undefined') {
      return;
    }
    const list = requestConversationId
      ? await getMangaAgentConversationMessages(id, requestConversationId)
      : await getMangaAgentMessages(id);
    if (chapterIdRef.current === requestChapterId && conversationIdRef.current === requestConversationId) {
      setMessages(toMessages(list));
    }
  }

  function handleAgentEvent(event: MangaAgentRunEvent): { reply?: string; waiting?: boolean } | Error | null {
    if (event.type === 'status') {
      setRunStatus(event.data.message || '智能体正在运行...');
      return null;
    }
    if (event.type === 'run_event') {
      const runEvent = event.data;
      setRunEvents((prev) => appendRunEvent(prev, runEvent));
      if (runEvent.label) setRunStatus(runEvent.label);
      if (runEvent.type === 'text_delta' && runEvent.text) {
        setDraftReply((prev) => prev + runEvent.text);
      }
      return null;
    }
    if (event.type === 'tool') {
      const toolLabel = event.data.tool || 'tool';
      setRunStatus(event.data.succeeded === false ? `${toolLabel} failed` : `${toolLabel} completed`);
      return null;
    }
    if (event.type === 'user_input_requested') {
      setUserInputRequest(event.data);
      setRunStatus(event.data.question || '需要你做出选择');
      return { waiting: true };
    }
    if (event.type === 'done') {
      return { reply: String(event.data.reply || '') };
    }
    if (event.type === 'error') {
      return new Error(String(event.data.detail || event.data.error || 'Agent request failed'));
    }
    if (event.type === 'ag_ui_event') {
      const agEvent = event.data;
      const rawEvent = agEvent.rawEvent as AgentRunTimelineEvent | undefined;
      if (agEvent.route) {
        setRoute(agEvent.route);
      }
      if (rawEvent) {
        setRunEvents((prev) => appendRunEvent(prev, rawEvent));
        if (rawEvent.label) setRunStatus(rawEvent.label);
      }
      if (agEvent.type === 'STATE_SNAPSHOT') {
        const message = agEvent.snapshot?.message;
        if (message) setRunStatus(message);
        if (agEvent.snapshot?.route) setRoute(agEvent.snapshot.route);
      }
      if (agEvent.type === 'RUN_STARTED') {
        setRunStatus('智能体已启动');
      }
      if (agEvent.type === 'RUN_FINISHED' && agEvent.outcome?.type === 'interrupt') {
        const interrupt = agEvent.outcome.interrupts?.[0];
        const metadata = interrupt?.metadata;
        const request = {
          requestId: agEvent.runId || activeRequestIdRef.current || undefined,
          question: String(metadata?.question || interrupt?.message || '需要你做出选择'),
          options: Array.isArray(metadata?.options) ? metadata.options : [],
          allowFreeText: Boolean(metadata?.allowFreeText),
          reason: interrupt?.reason || '',
        };
        setUserInputRequest(request);
        setRunStatus(request.question);
        return null;
      }
      if (agEvent.type === 'STEP_STARTED') {
        setRunStatus(String((agEvent as any).stepName || '模型推理中'));
      }
      if (agEvent.type === 'CUSTOM' && (agEvent as any).name === 'workflow_step') {
        const value = (agEvent as any).value as { node?: string; status?: string; route?: string; storyTitle?: string; chapterDisplayName?: string; sceneCount?: number; imageCount?: number; warnings?: string[] } | undefined;
        const node = String(value?.node || 'workflow');
        const status = String(value?.status || 'running');
        const title = ({
          ROUTING: 'Routing task',
          COLLECTING_CONTEXT: 'Collecting context',
          GENERATING: 'Generating content',
          EVALUATING: 'Evaluating result',
          WAITING_USER: 'Waiting for user decision',
          COMPLETED: 'Workflow complete',
        } as Record<string, string>)[node] || 'Workflow step';
        const detailParts: string[] = [];
        if (value?.route) detailParts.push(`route ${value.route}`);
        if (value?.storyTitle) detailParts.push(`story ${value.storyTitle}`);
        if (value?.chapterDisplayName) detailParts.push(`chapter ${value.chapterDisplayName}`);
        if (typeof value?.sceneCount === 'number') detailParts.push(`scenes ${value.sceneCount}`);
        if (typeof value?.imageCount === 'number') detailParts.push(`images ${value.imageCount}`);
        if (value?.warnings?.length) detailParts.push(`warnings ${value.warnings.join(', ')}`);
        setRunStatus(title);
        setRunEvents((prev) => appendRunEvent(prev, {
          type: 'workflow_step',
          phase: 'workflow',
          label: title,
          status,
          data: { type: 'workflow_step', node, status, route: value?.route, storyTitle: value?.storyTitle, chapterDisplayName: value?.chapterDisplayName, sceneCount: value?.sceneCount, imageCount: value?.imageCount, warnings: value?.warnings, detail: detailParts.join(' | ') },
          createdAt: new Date().toISOString(),
        }));
      }
    }
    return null;
  }

  async function startRun(message?: string) {
    const id = Number(chapterId);
    const resolvedConversationId = normalizeConversationId(conversationId);
    if (!id || !resolvedConversationId || loading) return;
    const text = (message ?? input).trim();
    if (!text) return;
    setLoading(true);
    setError('');
    setRunStatus('正在开始本次运行...');
    setDraftReply('');
    setUserInputRequest(null);
    setRunEvents([]);
    activeRequestIdRef.current = createRequestId();
    const requestId = activeRequestIdRef.current;
    try {
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = runMangaAgentAgUiStream(id, text, requestId || undefined, (event) => {
        const result = handleAgentEvent(event);
        if (result && 'reply' in result && result.reply) {
          setMessages((prev) => [...prev, { role: 'assistant', content: result.reply || '', requestId }]);
        }
      }, resolvedConversationId, route);
      setMessages((prev) => [...prev, { role: 'user', content: text, requestId }]);
      setInput('');
      clearRunPoll();
    } catch (err: any) {
      setError(err.message || 'Failed to start agent run');
    } finally {
      setLoading(false);
    }
  }

  async function resumeWithAnswer(answer: string) {
    const id = Number(chapterId);
    const resolvedConversationId = normalizeConversationId(conversationId);
    const requestId = userInputRequest?.requestId ?? userInputRequest?.request_id ?? activeRequestIdRef.current;
    if (!id || !resolvedConversationId || !requestId || loading) return;
    setLoading(true);
    setError('');
    setRunStatus('正在提交你的回答...');
    setUserInputRequest(null);
    setCustomAnswer('');
    try {
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = resumeMangaAgentAgUiStream(id, requestId, answer, (event) => {
        const result = handleAgentEvent(event);
        if (result && 'reply' in result && result.reply) {
          setMessages((prev) => [...prev, { role: 'assistant', content: result.reply || '', requestId }]);
        }
      }, resolvedConversationId);
      setMessages((prev) => [...prev, { role: 'system', content: `Answer submitted: ${answer}`, requestId }]);
    } catch (err: any) {
      setError(err.message || '提交回答失败');
    } finally {
      setLoading(false);
    }
  }

  async function cancelActiveRun() {
    const id = Number(chapterId);
    const resolvedConversationId = normalizeConversationId(conversationId);
    const requestId = activeRequestIdRef.current;
    if (!id || !resolvedConversationId || !requestId) return;
    setError('');
    setRunStatus('正在停止本次运行...');
    try {
      const snapshot = await cancelMangaAgentConversationRun(id, resolvedConversationId, requestId);
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      restoreRunSnapshot(snapshot);
      setRunStatus('本次运行已停止');
    } catch (err: any) {
      setError(err.message || '停止运行失败');
    }
  }

  async function startNewConversation() {
    const id = Number(chapterId);
    if (!id || loading) return;
    setError('');
    setHistoryLoading(true);
    try {
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      clearRunPoll();
      const conversation = await createMangaAgentConversation(id);
      const resolvedConversationId = normalizeConversationId((conversation as any)?.conversationId ?? (conversation as any)?.conversation_id);
      if (!resolvedConversationId) {
        throw new Error('新会话创建成功，但未返回会话标识');
      }
      setConversationId(resolvedConversationId);
      setMessages([]);
      setRunEvents([]);
      setDraftReply('');
      setUserInputRequest(null);
      setCustomAnswer('');
      setRunStatus('已开启新对话');
      activeRequestIdRef.current = null;
    } catch (err: any) {
      setError(err.message || '开启新对话失败');
    } finally {
      setHistoryLoading(false);
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
            <p className="text-sm text-gray-400">用于章节规划与分镜生成的对话工作区</p>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col gap-4 p-4 lg:flex-row">
        <aside className="w-full shrink-0 rounded-3xl border border-white/10 bg-black/20 p-4 backdrop-blur-sm lg:w-[320px]">
          <div className="space-y-4">
            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">Story</p>
              <select value={storyId} onChange={(e) => setStoryId(e.target.value)} className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50">
                {stories.length === 0 ? <option value="">暂无故事</option> : null}
                {stories.map((story) => (
                  <option key={story.id} value={story.id} className="bg-gray-900 text-gray-100">{story.title}</option>
                ))}
              </select>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">Chapter</p>
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
                      Chapter {chapter.chapter_number}
                    </option>
                  ))}
                </select>
                {chapterLoading && <Loader2 size={16} className="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-gray-500" />}
              </div>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">Route</p>
              <div className="grid gap-2">
                {ROUTE_OPTIONS.map((item) => {
                  const active = item.value === route;
                  return (
                    <button
                      key={item.value}
                      onClick={() => setRoute(item.value)}
                      className={`rounded-2xl border px-3 py-3 text-left transition ${active ? 'border-amber-300/40 bg-amber-300/10' : 'border-white/10 bg-white/[0.04] hover:border-white/20 hover:bg-white/[0.06]'}`}
                    >
                      <div className="flex items-center justify-between gap-3">
                        <div className="text-sm font-medium text-white">{item.label}</div>
                        <div className="text-[11px] uppercase tracking-[0.18em] text-gray-500">{item.value}</div>
                      </div>
                      <div className="mt-1 text-xs leading-5 text-gray-400">{item.description}</div>
                    </button>
                  );
                })}
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
                  <div className="text-xs text-gray-500">Chapter</div>
                  <div className="text-sm text-gray-100">{activeChapter ? `Chapter ${activeChapter.chapter_number}` : '未选择章节'}</div>
                </div>
                <div>
                  <div className="text-xs text-gray-500">Route</div>
                  <div className="text-sm text-gray-100">{activeRoute.label}</div>
                </div>
              </div>
              <button
                onClick={() => void startNewConversation()}
                disabled={!chapterId || loading || historyLoading}
                className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-amber-200/20 bg-black/20 px-3 py-2 text-sm text-amber-100 transition hover:border-amber-200/40 hover:bg-amber-200/10 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <Plus size={15} />
                新建对话
              </button>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">快捷提示</p>
              <div className="space-y-2">
                {STARTER_PROMPTS.map((prompt) => (
                  <button key={prompt} onClick={() => void startRun(prompt)} disabled={!chapterId || loading || historyLoading} className="w-full rounded-2xl border border-white/10 bg-white/[0.04] px-3 py-3 text-left text-sm text-gray-300 transition hover:border-amber-300/30 hover:bg-white/[0.08] hover:text-white disabled:cursor-not-allowed disabled:opacity-40">
                    {prompt}
                  </button>
                ))}
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
              <div className="text-xs text-gray-500">对话历史按章节和会话隔离保存</div>
            </div>
          </div>

          {error && <div className="mx-4 mt-4 rounded-2xl border border-red-500/30 bg-red-950/40 px-3 py-2 text-sm text-red-200">{error}</div>}

          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            {bootLoading || historyLoading ? (
              <div className="flex h-full items-center justify-center">
                <Loader2 size={28} className="animate-spin text-amber-300" />
              </div>
            ) : emptyState ? (
              <div className="flex h-full flex-col items-center justify-center px-6 text-center">
                <div className="mb-6 flex h-24 w-24 items-center justify-center rounded-full border border-white/10 bg-white/[0.04]">
                  <BookOpenText size={34} className="text-amber-200" />
                </div>
                <h2 className="text-3xl font-semibold text-white">漫画智能体</h2>
                <p className="mt-3 max-w-xl text-sm leading-7 text-gray-400">
                  Start here and use chat to drive storyboard generation. 请先选择故事和章节, then the agent can inspect context, generate scenes, and organize the next step.
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {messages.map((msg, idx) => (
                  <div key={`${msg.requestId || 'msg'}-${idx}`} className={msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
                    <div className={'max-w-[85%] rounded-3xl px-4 py-3 shadow-sm ' + (msg.role === 'user' ? 'whitespace-pre-wrap bg-amber-300 text-sm leading-7 text-gray-950' : msg.role === 'system' ? 'border border-red-400/20 bg-red-950/30 text-red-100' : 'border border-white/10 bg-white/[0.04] text-gray-200')}>
                      {msg.role === 'assistant' || msg.role === 'system' ? <MarkdownMessage content={msg.content} /> : msg.content}
                    </div>
                  </div>
                ))}

                {showExecutionPanel && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-4 text-sm text-gray-300">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${executionBadgeClass(userInputRequest ? 'waiting' : latestRunSummary?.tone || (loading ? 'thinking' : 'neutral'))}`}>
                          {executionIcon(userInputRequest ? 'waiting' : latestRunSummary?.tone || (loading ? 'thinking' : 'neutral'), userInputRequest ? 'question' : latestRunSummary?.icon || (loading ? 'sparkles' : 'clock'))}
                          {userInputRequest ? '等待你的决策' : loading ? '运行中' : '最近运行记录'}
                        </span>
                        {visibleRequestId && <span className="inline-flex items-center rounded-full border border-white/10 bg-black/20 px-3 py-1 text-xs text-gray-300">requestId {formatRequestId(visibleRequestId)}</span>}
                        {latestRunEvent?.createdAt && <span className="text-xs text-gray-500">{formatTimestamp(latestRunEvent.createdAt)}</span>}
                        {visibleRequestId && (loading || userInputRequest) && (
                          <button onClick={() => void cancelActiveRun()} className="inline-flex items-center gap-1 rounded-full border border-red-400/30 bg-red-950/30 px-3 py-1 text-xs text-red-100 transition hover:border-red-300/60 hover:bg-red-900/40">
                            <Square size={12} />
                            停止
                          </button>
                        )}
                      </div>
                      <div className="mt-3 text-sm text-gray-200">{runStatus}</div>
                      {latestRunSummary && (
                        <div className="mt-3 rounded-2xl border border-white/10 bg-black/15 px-4 py-3">
                          <div className="flex items-start gap-3">
                            <div className="mt-0.5">{executionIcon(latestRunSummary.tone, latestRunSummary.icon)}</div>
                            <div className="min-w-0 flex-1">
                              <div className="text-sm font-medium text-white">{latestRunSummary.title}</div>
                              <div className="mt-1 text-xs leading-5 text-gray-400">{latestRunSummary.detail}</div>
                            </div>
                          </div>
                        </div>
                      )}
                      {runEvents.length > 0 && (
                        <div className="mt-3 grid gap-2">
                          {runEvents.slice(-6).map((event, index) => {
                            const summary = timelineEventSummary(event);
                            return (
                              <div key={`${event.type}-${event.createdAt || index}`} className="flex items-start gap-3 rounded-2xl border border-white/10 bg-black/15 px-3 py-3">
                                <div className="mt-0.5">{executionIcon(summary.tone, summary.icon)}</div>
                                <div className="min-w-0 flex-1">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <span className="text-sm font-medium text-white">{summary.title}</span>
                                    <span className="text-[11px] uppercase tracking-[0.18em] text-gray-500">{event.type}</span>
                                  </div>
                                  <div className="mt-1 text-xs leading-5 text-gray-400">{summary.detail}</div>
                                </div>
                                {event.createdAt && <div className="shrink-0 text-[11px] text-gray-500">{formatTimestamp(event.createdAt)}</div>}
                              </div>
                            );
                          })}
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
                      <div className="text-xs uppercase tracking-[0.18em] text-amber-200/70">需要你做出选择</div>
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
                          <input value={customAnswer} onChange={(e) => setCustomAnswer(e.target.value)} placeholder="Type another answer" className="min-w-0 flex-1 rounded-2xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-gray-100 outline-none focus:border-amber-300/40" />
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
                placeholder={chapterId ? '例如：检查这一章能否直接转成分镜' : '请先选择故事和章节'}
                className="min-h-[58px] flex-1 resize-none rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-gray-100 outline-none transition placeholder:text-gray-500 focus:border-amber-300/40"
              />
              <button
                onClick={() => void startRun()}
                disabled={loading || historyLoading || !chapterId || !input.trim()}
                className="inline-flex h-auto min-w-[110px] items-center justify-center gap-2 rounded-3xl bg-amber-300 px-4 py-3 text-sm font-medium text-gray-950 transition hover:bg-amber-200 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {loading ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                发送
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}

