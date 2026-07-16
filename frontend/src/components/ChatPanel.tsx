import { useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { FileText, History, Image, MessageSquare, Save, Send, Sparkles, Square, X } from 'lucide-react';
import {
  API_KEY_CHANGE_EVENT,
  chatStream,
  commitNovelContentProposal,
  createNovelContentProposal,
  getPrimaryProviderModel,
  getProviderModelOptions,
  listAiConversations,
  listNovelRevisions,
  renameAiConversation,
  restoreNovelRevision,
  saveNovelContent,
  updateNovelContentProposal,
  type AiConversationSummary,
  type Chapter,
  type ChatMessage,
  type NovelContentProposal,
  type NovelRevision,
} from '../api';
import MarkdownRenderer from './MarkdownRenderer';
import ModelSwitcher from './ModelSwitcher';
import InlineConversationTitle from './InlineConversationTitle';

type Mode = 'chat' | 'original';
type ReviewTab = 'current' | 'candidate';
type DisplayMessage = ChatMessage & { local_id?: string };
const MAX_ORIGINAL_CHARS = 50000;

interface Props {
  chapter: Chapter | null;
  onMessageSent?: () => void | Promise<void>;
  onChapterRefresh?: (chapterId: number) => void | Promise<void>;
  onGoToManga?: () => void;
}

interface ProposalReviewState {
  proposal: NovelContentProposal;
  original: string;
  draft: string;
  contentHash: string;
  error: string;
  tab: ReviewTab;
}

export default function ChatPanel({ chapter, onMessageSent, onChapterRefresh, onGoToManga }: Props) {
  const [messages, setMessages] = useState<DisplayMessage[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamContent, setStreamContent] = useState('');
  const [mode, setMode] = useState<Mode>('chat');
  const [selectedLlmModel, setSelectedLlmModel] = useState(() => getPrimaryProviderModel('llm'));
  const [originalText, setOriginalText] = useState('');
  const [serverOriginalText, setServerOriginalText] = useState('');
  const [writeBusy, setWriteBusy] = useState(false);
  const [proposalBusy, setProposalBusy] = useState(false);
  const [originalError, setOriginalError] = useState('');
  const [originalNotice, setOriginalNotice] = useState('');
  const [revisions, setRevisions] = useState<NovelRevision[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [conversation, setConversation] = useState<AiConversationSummary | null>(null);
  const [review, setReview] = useState<ProposalReviewState | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamingChapterIdRef = useRef<number | null>(null);
  const userScrolledUp = useRef(false);

  const originalDirty = originalText !== serverOriginalText;
  const latestEligibleAssistantId = useMemo(() => {
    if (streaming) return null;
    const last = [...messages].reverse().find((message) =>
      message.role === 'assistant'
      && typeof message.id === 'number'
      && (message.completion_status ?? 'complete') === 'complete',
    );
    return last?.id ?? null;
  }, [messages, streaming]);

  const refreshRevisions = async (chapterId: number) => setRevisions(await listNovelRevisions(chapterId));
  const autoResize = (el: HTMLTextAreaElement) => { el.style.height = 'auto'; el.style.height = `${el.scrollHeight}px`; };

  useEffect(() => {
    if (abortRef.current) {
      const chapterId = streamingChapterIdRef.current;
      abortRef.current.abort();
      abortRef.current = null;
      if (chapterId !== null) window.setTimeout(() => void onChapterRefresh?.(chapterId), 500);
    }
    streamingChapterIdRef.current = null;
    if (chapter) {
      setMessages(getDisplayMessages(chapter));
      const text = chapter.novel_content || '';
      setOriginalText(text);
      setServerOriginalText(text);
      void refreshRevisions(chapter.id).catch(() => setRevisions([]));
    } else {
      setMessages([]);
      setOriginalText('');
      setServerOriginalText('');
      setRevisions([]);
    }
    setReview(null);
    setMode('chat');
    setOriginalError('');
    setOriginalNotice('');
    setStreamContent('');
    setStreaming(false);
  }, [chapter?.id, chapter?.version]);

  useEffect(() => {
    if (!chapter) { setConversation(null); return; }
    void listAiConversations('STORY_CHAT', chapter.id)
      .then((items) => setConversation(items[0] ?? null))
      .catch(() => setConversation(null));
  }, [chapter?.id, messages.length]);

  useEffect(() => {
    if (!userScrolledUp.current) messagesEndRef.current?.scrollIntoView({ behavior: streaming ? 'instant' : 'smooth' });
  }, [messages, streamContent, streaming]);
  useEffect(() => { userScrolledUp.current = false; }, [messages.length]);
  useEffect(() => {
    const element = scrollContainerRef.current;
    if (!element) return;
    const onScroll = () => { userScrolledUp.current = element.scrollHeight - element.scrollTop - element.clientHeight >= 80; };
    element.addEventListener('scroll', onScroll, { passive: true });
    return () => element.removeEventListener('scroll', onScroll);
  }, []);
  useEffect(() => {
    const syncModels = () => {
      const options = getProviderModelOptions('llm');
      setSelectedLlmModel((previous) => previous && options.includes(previous) ? previous : (options[0] || ''));
    };
    syncModels();
    window.addEventListener(API_KEY_CHANGE_EVENT, syncModels);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, syncModels);
  }, []);

  const handleSend = () => {
    if (!input.trim() || !chapter || streaming) return;
    const userMessage: DisplayMessage = {
      local_id: `local-user-${Date.now()}`,
      id: Date.now(),
      role: 'user',
      content: input.trim(),
      completion_status: 'complete',
      created_at: new Date().toISOString(),
    };
    setMessages((previous) => [...previous, userMessage]);
    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    setStreaming(true);
    setStreamContent('');
    let accumulated = '';
    streamingChapterIdRef.current = chapter.id;
    abortRef.current = chatStream(chapter.id, userMessage.content,
      (token) => { accumulated += token; setStreamContent(accumulated); },
      async (message) => {
        abortRef.current = null;
        streamingChapterIdRef.current = null;
        setMessages((previous) => [...previous, message]);
        setStreamContent('');
        setStreaming(false);
        await onMessageSent?.();
      },
      (error) => {
        abortRef.current = null;
        streamingChapterIdRef.current = null;
        setMessages((previous) => [...previous, {
          local_id: `local-error-${Date.now()}`,
          id: Date.now(),
          role: 'assistant',
          content: `错误：${error}`,
          completion_status: 'partial',
          created_at: new Date().toISOString(),
        }]);
        setStreamContent('');
        setStreaming(false);
      },
      selectedLlmModel);
  };

  const handleAbort = () => {
    const chapterId = streamingChapterIdRef.current;
    abortRef.current?.abort();
    abortRef.current = null;
    streamingChapterIdRef.current = null;
    if (streamContent) {
      setMessages((previous) => [...previous, {
        local_id: `local-abort-${Date.now()}`,
        id: Date.now(),
        role: 'assistant',
        content: `${streamContent}\n\n[已中止]`,
        completion_status: 'partial',
        created_at: new Date().toISOString(),
      }]);
    }
    setStreamContent('');
    setStreaming(false);
    window.setTimeout(() => chapterId !== null ? void onChapterRefresh?.(chapterId) : void onMessageSent?.(), 500);
  };

  const handleOriginalSave = async () => {
    if (!chapter || writeBusy) return;
    const content = originalText.trim();
    if (!content) { setOriginalError('请输入小说原文。'); return; }
    if (content.length > MAX_ORIGINAL_CHARS) { setOriginalError(`内容不能超过 ${MAX_ORIGINAL_CHARS} 字。`); return; }
    if (chapter.version === undefined) { setOriginalError('章节版本缺失，请刷新后再保存。'); return; }
    setWriteBusy(true); setOriginalError(''); setOriginalNotice('');
    try {
      const result = await saveNovelContent(chapter.id, content, chapter.version);
      setOriginalText(content);
      setServerOriginalText(content);
      setOriginalNotice('小说原文已保存。');
      await refreshRevisions(chapter.id);
      await onChapterRefresh?.(chapter.id);
      if (result.chapter_version !== undefined) setOriginalNotice(`小说原文已保存，当前版本 ${result.chapter_version}。`);
    } catch (error: any) {
      setOriginalError(error.message || '保存失败。');
    } finally {
      setWriteBusy(false);
    }
  };

  const handleCreateProposal = async (message: DisplayMessage) => {
    if (!chapter || proposalBusy || writeBusy || typeof message.id !== 'number') return;
    if (chapter.version === undefined) { setOriginalError('章节版本缺失，请刷新后再整理。'); setMode('original'); return; }
    if (originalDirty) { setOriginalError('小说原文有未保存修改，请先保存或放弃修改后再整理正文草稿。'); setMode('original'); return; }
    const activeConversation = conversation ?? (await listAiConversations('STORY_CHAT', chapter.id).then((items) => items[0] ?? null));
    if (!activeConversation) { setOriginalError('未找到当前章节对话，请先发送一条消息后再整理。'); return; }
    setProposalBusy(true); setOriginalError(''); setOriginalNotice('');
    try {
      const proposal = await createNovelContentProposal(
        chapter.id,
        activeConversation.conversationId,
        message.id,
        chapter.version,
        selectedLlmModel,
      );
      setReview({
        proposal,
        original: serverOriginalText,
        draft: proposal.content,
        contentHash: proposal.content_hash,
        error: '',
        tab: 'candidate',
      });
    } catch (error: any) {
      setOriginalError(error.message || '整理正文草稿失败。');
    } finally {
      setProposalBusy(false);
    }
  };

  const closeReview = () => {
    if (!review) return;
    if (review.draft !== review.proposal.content && !window.confirm('候选正文有未提交修改，确定丢弃吗？')) return;
    setReview(null);
  };

  const commitReview = async () => {
    if (!chapter || !review || writeBusy || chapter.version === undefined) return;
    setWriteBusy(true);
    setReview((current) => current ? { ...current, error: '' } : current);
    try {
      let currentProposal = review.proposal;
      let currentHash = review.contentHash;
      if (review.draft !== review.proposal.content) {
        currentProposal = await updateNovelContentProposal(chapter.id, review.proposal.proposal_id, review.draft, review.contentHash);
        currentHash = currentProposal.content_hash;
      }
      const result = await commitNovelContentProposal(chapter.id, currentProposal.proposal_id, chapter.version, currentHash);
      setOriginalText(review.draft.trim());
      setServerOriginalText(review.draft.trim());
      await refreshRevisions(chapter.id);
      await onChapterRefresh?.(chapter.id);
      setReview(null);
      setMode('original');
      setOriginalNotice(result.changed ? '已确认替换原文，可从历史版本恢复。' : '候选正文与当前原文一致，未创建新版本。');
    } catch (error: any) {
      setReview((current) => current ? { ...current, error: error.message || '确认替换失败。候选内容已保留。' } : current);
    } finally {
      setWriteBusy(false);
    }
  };

  const handleRestore = async (revision: NovelRevision) => {
    if (!chapter || writeBusy || chapter.version === undefined) return;
    setWriteBusy(true); setOriginalError('');
    try {
      await restoreNovelRevision(chapter.id, revision.id, chapter.version);
      setOriginalText(revision.content);
      setServerOriginalText(revision.content);
      setOriginalNotice(`已恢复版本 ${revision.revision_number}，并创建新版本。`);
      await refreshRevisions(chapter.id);
      await onChapterRefresh?.(chapter.id);
    } catch (error: any) {
      setOriginalError(error.message || '恢复失败。');
    } finally {
      setWriteBusy(false);
    }
  };

  return <div className="flex h-full flex-col bg-bg-base">
    <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-2.5">
      <div className="min-w-0 shrink">{conversation ? <InlineConversationTitle title={conversation.title} onSave={async (title) => setConversation(await renameAiConversation(conversation.conversationId, title))} /> : <h2 className="text-sm font-semibold tracking-wide text-text-secondary">创作工作区</h2>}</div>
      <div className="flex items-center gap-0.5" role="tablist" aria-label="创作模式">
        <button onClick={() => setMode('chat')} disabled={streaming} role="tab" aria-selected={mode === 'chat'} className={`relative px-3 py-1.5 text-xs font-medium transition-colors ${mode === 'chat' ? 'text-accent after:absolute after:bottom-0 after:left-2 after:right-2 after:h-0.5 after:rounded-full after:bg-accent' : 'text-text-secondary hover:text-text-primary disabled:opacity-30'}`}><span className="flex items-center gap-1.5"><MessageSquare size={12} />AI 对话</span></button>
        <button onClick={() => setMode('original')} disabled={streaming} role="tab" aria-selected={mode === 'original'} className={`relative px-3 py-1.5 text-xs font-medium transition-colors ${mode === 'original' ? 'text-accent after:absolute after:bottom-0 after:left-2 after:right-2 after:h-0.5 after:rounded-full after:bg-accent' : 'text-text-secondary hover:text-text-primary disabled:opacity-30'}`}><span className="flex items-center gap-1.5"><FileText size={12} />小说原文</span></button>
      </div>
    </div>

    {mode === 'original' ? <div className="flex min-h-0 flex-1 flex-col">
      <div className="shrink-0 px-4 pb-2 pt-3 text-xs leading-relaxed text-text-secondary">小说原文是章节正式正文。手工保存、恢复和 AI 草稿确认都会创建历史版本。</div>
      <div className="min-h-0 flex-1 px-4 pb-3"><textarea value={originalText} onChange={(event) => { setOriginalText(event.target.value); setOriginalError(''); setOriginalNotice(''); }} placeholder={`粘贴或撰写小说原文，最长 ${MAX_ORIGINAL_CHARS} 字`} className="h-full w-full resize-none rounded-lg border border-border bg-bg-surface p-3 text-sm leading-relaxed text-text-primary outline-none transition-colors placeholder:text-text-muted focus:border-accent" /></div>
      {showHistory && <div className="mx-4 mb-3 max-h-36 overflow-y-auto rounded-lg border border-border bg-bg-base p-2 text-xs">{revisions.length === 0 ? <p className="px-2 py-1 text-text-muted">暂无历史版本。</p> : revisions.map((revision) => <div key={revision.id} className="flex items-center gap-2 rounded px-2 py-1.5 hover:bg-bg-surface"><span className="font-medium text-text-primary">v{revision.revision_number}</span><span className="text-text-muted">{revision.source}</span><span className="ml-auto text-text-muted">{new Date(revision.created_at).toLocaleString()}</span><button type="button" onClick={() => handleRestore(revision)} disabled={writeBusy} className="text-accent hover:text-accent-hover disabled:opacity-40">恢复</button></div>)}</div>}
      <div className="flex shrink-0 items-center justify-between gap-3 px-4 pb-3"><div className="text-xs text-text-secondary">{originalText.length.toLocaleString()} / {MAX_ORIGINAL_CHARS.toLocaleString()} 字{originalDirty && <span className="ml-3 text-amber-600">未保存</span>}{originalError && <span className="ml-3 text-red-600" role="alert">{originalError}</span>} {originalNotice && <span className="ml-3 text-accent">{originalNotice}</span>}</div><div className="flex items-center gap-2"><button type="button" onClick={() => setShowHistory((value) => !value)} className="flex items-center gap-1.5 rounded-md border border-border px-3 py-2 text-xs font-medium text-text-secondary transition-colors hover:text-text-primary" aria-expanded={showHistory}><History size={13} />历史{revisions.length ? ` (${revisions.length})` : ''}</button><button type="button" onClick={handleOriginalSave} disabled={!chapter || writeBusy || !originalText.trim()} className="flex items-center gap-1.5 rounded-md bg-accent px-4 py-2 text-xs font-medium text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-30"><Save size={13} />{writeBusy ? '保存中...' : '保存原文'}</button></div></div>
    </div> : <>
      <div ref={scrollContainerRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {messages.length === 0 && !streaming && <div className="flex h-full items-center justify-center text-sm text-text-muted">开始和 AI 讨论你的小说创意。</div>}
        {messages.map((message) => <div key={message.local_id ?? message.id} className={`flex ${message.role === 'user' ? 'justify-end' : 'w-full justify-start'}`}><div className={`rounded-xl px-4 py-3 text-sm leading-relaxed ${message.role === 'user' ? 'max-w-[80%] rounded-br-sm bg-accent-muted/40 text-text-primary' : 'w-full rounded-bl-sm border border-border bg-bg-raised text-text-primary shadow-sm'}`}><MarkdownRenderer content={message.content} />{message.role === 'assistant' && message.id === latestEligibleAssistantId && <div className="mt-3 flex justify-end border-t border-border/70 pt-2"><button type="button" onClick={() => handleCreateProposal(message)} disabled={proposalBusy || writeBusy || originalDirty} className="flex min-h-9 items-center gap-1.5 rounded-md border border-accent/25 bg-accent-soft px-2.5 py-1.5 text-xs font-medium text-accent transition-colors hover:bg-accent-muted/40 disabled:cursor-not-allowed disabled:opacity-40"><Sparkles size={13} />{proposalBusy ? '整理中...' : '整理为正文草稿'}</button></div>}</div></div>)}
        {streaming && <div className="flex justify-start"><div className="w-full rounded-xl rounded-bl-sm border border-border bg-bg-raised px-4 py-3 text-sm leading-relaxed text-text-primary shadow-sm">{streamContent ? <><MarkdownRenderer content={streamContent} /><span className="ml-0.5 inline-block h-4 w-1.5 rounded-sm bg-accent" /></> : 'AI 思考中...'}</div></div>}
        {onGoToManga && messages.length > 0 && !streaming && <div className="flex justify-center py-3"><button type="button" onClick={onGoToManga} className="flex items-center gap-1.5 rounded-md border border-accent-secondary/20 bg-accent-secondary/10 px-4 py-2 text-xs font-medium text-accent-secondary transition-colors"><Image size={14} />查看漫画 / 生成分镜</button></div>}
        <div ref={messagesEndRef} />
      </div>
      <div className="border-t border-border/80 bg-bg-base/80 px-3 py-3 backdrop-blur-md md:px-4">
        <div className="mb-2 flex justify-end"><ModelSwitcher capability="llm" selectedModel={selectedLlmModel} onSelect={setSelectedLlmModel} disabled={streaming || proposalBusy} /></div>
        <div className="group relative overflow-hidden rounded-2xl border border-border bg-bg-surface/95 shadow-lg shadow-black/10 transition-all duration-200 focus-within:border-accent/70 focus-within:bg-bg-raised focus-within:shadow-[0_0_0_3px_var(--color-accent-muted)]">
          <div className="flex items-end gap-3 px-3 py-3">
            <div className="mb-0.5 hidden h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-border bg-bg-base text-accent shadow-sm md:flex"><Sparkles size={15} aria-hidden="true" /></div>
            <textarea ref={textareaRef} value={input} onChange={(event) => { setInput(event.target.value); autoResize(event.target); }} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); handleSend(); } }} placeholder="描述你的小说想法，或要求 AI 润色、改写、续写..." rows={1} className="min-h-10 flex-1 resize-none bg-transparent py-2 text-[15px] leading-6 text-text-primary outline-none placeholder:text-text-muted/80" style={{ maxHeight: '160px', overflow: 'auto' }} />
            {streaming ? <button type="button" onClick={handleAbort} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-white shadow-md shadow-black/20 transition-all duration-200 hover:bg-accent-hover hover:shadow-lg" title="停止生成" aria-label="停止生成"><Square size={17} /></button> : <button type="button" onClick={handleSend} disabled={!input.trim() || proposalBusy} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-white shadow-md shadow-black/20 transition-all duration-200 hover:bg-accent-hover hover:shadow-lg disabled:cursor-not-allowed disabled:bg-bg-raised disabled:text-text-muted disabled:shadow-none" title="发送消息" aria-label="发送消息"><Send size={17} /></button>}
          </div>
        </div>
      </div>
    </>}
    {review && <ProposalReviewDialog review={review} setReview={setReview} onClose={closeReview} onCommit={commitReview} committing={writeBusy} />}
  </div>;
}

function ProposalReviewDialog({
  review,
  setReview,
  onClose,
  onCommit,
  committing,
}: {
  review: ProposalReviewState;
  setReview: Dispatch<SetStateAction<ProposalReviewState | null>>;
  onClose: () => void;
  onCommit: () => void;
  committing: boolean;
}) {
  const delta = review.draft.length - review.original.length;
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3" role="dialog" aria-modal="true" aria-label="正文草稿确认">
    <div className="flex max-h-[92dvh] w-full max-w-6xl flex-col rounded-lg border border-border bg-bg-base shadow-xl">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div><h3 className="text-sm font-semibold text-text-primary">确认正文草稿</h3><p className="text-xs text-text-secondary">字数 {review.draft.length.toLocaleString()}，较当前原文 {delta >= 0 ? '+' : ''}{delta.toLocaleString()}</p></div>
        <button type="button" onClick={onClose} className="flex h-9 w-9 items-center justify-center rounded-md text-text-secondary hover:bg-bg-surface hover:text-text-primary" aria-label="关闭"><X size={16} /></button>
      </div>
      <div className="border-b border-border px-4 py-2 md:hidden">
        <div className="grid grid-cols-2 rounded-md border border-border p-0.5">
          <button type="button" onClick={() => setReview((value) => value ? { ...value, tab: 'current' } : value)} className={`rounded px-3 py-2 text-xs ${review.tab === 'current' ? 'bg-bg-raised text-text-primary' : 'text-text-secondary'}`}>当前原文</button>
          <button type="button" onClick={() => setReview((value) => value ? { ...value, tab: 'candidate' } : value)} className={`rounded px-3 py-2 text-xs ${review.tab === 'candidate' ? 'bg-bg-raised text-text-primary' : 'text-text-secondary'}`}>正文候选</button>
        </div>
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 overflow-hidden p-4 md:grid-cols-2">
        <section className={`${review.tab === 'current' ? 'block' : 'hidden'} min-h-[55dvh] flex-col md:flex`}>
          <div className="mb-2 text-xs font-medium text-text-secondary">当前原文（只读）</div>
          <div className="h-full overflow-y-auto rounded-md border border-border bg-bg-surface p-3 text-sm leading-relaxed whitespace-pre-wrap text-text-secondary">{review.original || '当前章节暂无原文。'}</div>
        </section>
        <section className={`${review.tab === 'candidate' ? 'block' : 'hidden'} min-h-[55dvh] flex-col md:flex`}>
          <div className="mb-2 text-xs font-medium text-text-secondary">正文候选（可编辑）</div>
          <textarea value={review.draft} onChange={(event) => setReview((value) => value ? { ...value, draft: event.target.value, error: '' } : value)} className="h-full min-h-[55dvh] resize-none rounded-md border border-border bg-bg-surface p-3 text-sm leading-relaxed text-text-primary outline-none focus:border-accent" />
        </section>
      </div>
      {review.error && <div className="px-4 pb-2 text-xs text-red-600" role="alert">{review.error}</div>}
      <div className="flex items-center justify-end gap-2 border-t border-border px-4 py-3">
        <button type="button" onClick={onClose} disabled={committing} className="rounded-md border border-border px-4 py-2 text-xs font-medium text-text-secondary hover:text-text-primary disabled:opacity-40">取消</button>
        <button type="button" onClick={onCommit} disabled={committing || !review.draft.trim()} className="rounded-md bg-accent px-4 py-2 text-xs font-medium text-white hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40">{committing ? '确认中...' : '确认替换原文（可从历史恢复）'}</button>
      </div>
    </div>
  </div>;
}

function getDisplayMessages(chapter: Chapter): DisplayMessage[] {
  const sourceMessages = chapter.messages ?? [];
  return sourceMessages
    .filter((message) => !isLegacyImportedOriginalMirror(chapter, message))
    .map((message) => ({ ...message, completion_status: message.completion_status ?? 'complete' }));
}

function isLegacyImportedOriginalMirror(chapter: Chapter, message: Pick<DisplayMessage, 'role' | 'content'>): boolean {
  if (chapter.content_source !== 'import') return false;
  const originalText = (chapter.novel_content ?? '').trim();
  return originalText.length > 0 && message.role === 'user' && message.content.trim() === originalText;
}
