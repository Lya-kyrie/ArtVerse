import { useEffect, useRef, useState } from 'react';
import { Send, Image, Square, MessageSquare, FileText, Save } from 'lucide-react';
import { API_KEY_CHANGE_EVENT, chatStream, getPrimaryProviderModel, getProviderModelOptions, importNovel, listAiConversations, renameAiConversation, type AiConversationSummary, type Chapter } from '../api';
import MarkdownRenderer from './MarkdownRenderer';
import ModelSwitcher from './ModelSwitcher';
import InlineConversationTitle from './InlineConversationTitle';

type Mode = 'chat' | 'import';
const MAX_IMPORT_CHARS = 50000;

interface Props {
  chapter: Chapter | null;
  onMessageSent?: () => void;
  onChapterRefresh?: (chapterId: number) => void;
  onGoToManga?: () => void;
}

export default function ChatPanel({ chapter, onMessageSent, onChapterRefresh, onGoToManga }: Props) {
  const [messages, setMessages] = useState<{ role: string; content: string }[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamContent, setStreamContent] = useState('');
  const [mode, setMode] = useState<Mode>('chat');
  const [selectedLlmModel, setSelectedLlmModel] = useState(() => getPrimaryProviderModel('llm'));
  const [importText, setImportText] = useState('');
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState('');
  const [conversation, setConversation] = useState<AiConversationSummary | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamingChapterIdRef = useRef<number | null>(null);
  const userScrolledUp = useRef(false);
  const source = chapter?.content_source ?? null;
  const isImportLocked = source === 'import';
  const isChatLocked = source === 'chat' || (!!chapter && !source && chapter.messages.length > 0);

  const autoResize = (el: HTMLTextAreaElement) => {
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  };

  useEffect(() => {
    // Abort any in-progress stream when switching chapters
    if (abortRef.current) {
      const abortedChapterId = streamingChapterIdRef.current;
      abortRef.current.abort();
      abortRef.current = null;
      if (abortedChapterId !== null) {
        window.setTimeout(() => onChapterRefresh?.(abortedChapterId), 500);
      }
    }
    streamingChapterIdRef.current = null;
    if (chapter) {
      setMessages(chapter.messages.map((m) => ({ role: m.role, content: m.content })));
      setImportText(chapter.content_source === 'import' ? chapter.novel_content || '' : '');
      setMode(chapter.content_source === 'import' ? 'import' : 'chat');
    } else {
      setMessages([]);
      setImportText('');
      setMode('chat');
    }
    setImportError('');
    setStreamContent('');
    setStreaming(false);
  }, [chapter?.id]);

  useEffect(() => {
    if (!chapter) { setConversation(null); return; }
    void listAiConversations('STORY_CHAT', chapter.id).then(items => setConversation(items[0] ?? null)).catch(() => setConversation(null));
  }, [chapter?.id]);

  // Auto-scroll only if user hasn't scrolled up.
  // Use instant scroll during streaming to avoid animation fighting with user scroll.
  useEffect(() => {
    if (!userScrolledUp.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: streaming ? 'instant' : 'smooth' });
    }
  }, [messages, streamContent]);

  // Reset scroll lock when user sends a new message
  useEffect(() => {
    userScrolledUp.current = false;
  }, [messages.length]);

  // Detect manual scroll
  useEffect(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    const handleScroll = () => {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
      userScrolledUp.current = !atBottom;
    };
    el.addEventListener('scroll', handleScroll, { passive: true });
    return () => el.removeEventListener('scroll', handleScroll);
  }, []);

  useEffect(() => {
    const syncModels = () => {
      const options = getProviderModelOptions('llm');
      setSelectedLlmModel((prev) => (prev && options.includes(prev) ? prev : (options[0] || '')));
    };
    syncModels();
    window.addEventListener(API_KEY_CHANGE_EVENT, syncModels);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, syncModels);
  }, []);

  const handleSend = () => {
    if (!input.trim() || !chapter || streaming || isImportLocked) return;
    const userMsg = { role: 'user', content: input.trim() };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    setStreaming(true);
    setStreamContent('');

    let accumulated = '';
    streamingChapterIdRef.current = chapter.id;
    abortRef.current = chatStream(
      chapter.id,
      userMsg.content,
      (token) => {
        accumulated += token;
        setStreamContent(accumulated);
      },
      (fullContent) => {
        abortRef.current = null;
        streamingChapterIdRef.current = null;
        setMessages((prev) => [...prev, { role: 'assistant', content: fullContent }]);
        setStreamContent('');
        setStreaming(false);
        onMessageSent?.();
      },
      (err) => {
        abortRef.current = null;
        streamingChapterIdRef.current = null;
        setMessages((prev) => [...prev, { role: 'assistant', content: `错误: ${err}` }]);
        setStreamContent('');
        setStreaming(false);
      },
      selectedLlmModel,
    );
  };

  const handleAbort = () => {
    const abortedChapterId = streamingChapterIdRef.current;
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    streamingChapterIdRef.current = null;
    if (streamContent) {
      setMessages((prev) => [...prev, { role: 'assistant', content: streamContent + '\n\n[已中止]' }]);
    }
    setStreamContent('');
    setStreaming(false);
    window.setTimeout(() => {
      if (abortedChapterId !== null) {
        onChapterRefresh?.(abortedChapterId);
      } else {
        onMessageSent?.();
      }
    }, 500);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleImportSave = async () => {
    if (!chapter || importing || isChatLocked) return;
    const text = importText.trim();
    if (!text) {
      setImportError('请输入小说内容');
      return;
    }
    if (text.length > MAX_IMPORT_CHARS) {
      setImportError(`内容过长，请控制在 ${MAX_IMPORT_CHARS} 字以内（当前 ${text.length} 字）`);
      return;
    }
    setImporting(true);
    setImportError('');
    try {
      const updated = await importNovel(chapter.id, text);
      setMessages(updated.messages.map((m) => ({ role: m.role, content: m.content })));
      setImportText(updated.novel_content || text);
      onChapterRefresh?.(chapter.id);
    } catch (err: any) {
      setImportError(err.message || '保存失败');
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="flex flex-col h-full bg-bg-base">
      {/* Header with mode tabs */}
      <div className="px-4 py-2.5 border-b border-border flex items-center justify-between gap-3">
        <div className="min-w-0 shrink">
          {conversation ? <InlineConversationTitle title={conversation.title} onSave={async (title) => {
            const updated = await renameAiConversation(conversation.conversationId, title);
            setConversation(updated);
          }} /> : <h2 className="text-sm font-semibold text-text-secondary tracking-wide">会话</h2>}
        </div>
        <div className="flex items-center gap-0.5">
          <button
            onClick={() => setMode('chat')}
            disabled={streaming || isImportLocked}
            className={`px-3 py-1.5 text-xs font-medium transition-colors relative ${
              mode === 'chat'
                ? 'text-accent after:absolute after:bottom-0 after:left-2 after:right-2 after:h-0.5 after:rounded-full after:bg-accent'
                : 'text-text-secondary hover:text-text-primary disabled:opacity-30 disabled:cursor-not-allowed'
            }`}
          >
            <span className="flex items-center gap-1.5">
              <MessageSquare size={12} />
              AI 对话
            </span>
          </button>
          <button
            onClick={() => setMode('import')}
            disabled={streaming || isChatLocked}
            className={`px-3 py-1.5 text-xs font-medium transition-colors relative ${
              mode === 'import'
                ? 'text-accent after:absolute after:bottom-0 after:left-2 after:right-2 after:h-0.5 after:rounded-full after:bg-accent'
                : 'text-text-secondary hover:text-text-primary disabled:opacity-30 disabled:cursor-not-allowed'
            }`}
          >
            <span className="flex items-center gap-1.5">
              <FileText size={12} />
              粘贴小说
            </span>
          </button>
        </div>
      </div>

      {mode === 'import' ? (
        <div className="flex-1 flex flex-col min-h-0">
          <div className="px-4 pt-3 pb-2 text-xs text-text-secondary leading-relaxed shrink-0">
            {isImportLocked
              ? '本话已导入小说，不能再使用 AI 对话。右侧「漫画」面板可继续生成分镜与漫画图片。'
              : isChatLocked
                ? '本话已使用 AI 对话创作，不能再粘贴小说。请新建下一话后导入已有小说。'
                : '将你已有的小说内容粘贴到下方，保存后本话将锁定为「粘贴小说」模式。'}
          </div>
          <div className="flex-1 px-4 pb-3 min-h-0">
            <textarea
              value={importText}
              onChange={(e) => {
                setImportText(e.target.value);
                if (importError) setImportError('');
              }}
              disabled={isImportLocked || isChatLocked}
              placeholder={`粘贴小说全文…（最长 ${MAX_IMPORT_CHARS} 字）`}
              className="w-full h-full bg-bg-surface border border-border rounded-lg p-3 text-sm text-text-primary
                         placeholder-text-muted resize-none outline-none focus:border-accent transition-colors disabled:opacity-70
                         leading-relaxed"
            />
          </div>
          <div className="px-4 pb-3 shrink-0 flex items-center justify-between gap-3">
            <div className="text-xs text-text-secondary">
              {importText.length.toLocaleString()} / {MAX_IMPORT_CHARS.toLocaleString()} 字
              {importError && <span className="ml-3 text-accent">{importError}</span>}
            </div>
            <button
              onClick={handleImportSave}
              disabled={!chapter || importing || isImportLocked || isChatLocked || !importText.trim()}
              className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded-md
                         bg-accent hover:bg-accent-hover text-white
                         disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <Save size={13} />
              {isImportLocked ? '已导入' : importing ? '保存中…' : '保存小说'}
            </button>
          </div>
        </div>
      ) : (
        <>
          {/* Messages */}
          <div ref={scrollContainerRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
            {messages.length === 0 && !streaming && (
              <div className="flex items-center justify-center h-full text-text-muted text-sm">
                开始和 AI 讨论你的小说创意吧…
              </div>
            )}
            {messages.map((msg, i) => (
              <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start w-full'}`}>
                <div
                  className={`px-4 py-3 rounded-xl text-sm leading-relaxed ${
                    msg.role === 'user'
                      ? 'max-w-[80%] bg-accent-muted/40 text-text-primary rounded-br-sm'
                      : 'w-full bg-bg-raised border border-border text-text-primary rounded-bl-sm shadow-sm'
                  }`}
                >
                  <MarkdownRenderer content={msg.content} />
                </div>
              </div>
            ))}
            {streaming && !streamContent && (
              <div className="flex justify-start">
                <div className="flex items-center gap-3 px-4 py-3 rounded-xl rounded-bl-sm bg-bg-raised border border-border shadow-sm">
                  <svg className="w-5 h-5 animate-spin text-accent" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  <span className="text-sm text-text-secondary">AI 思考中…</span>
                </div>
              </div>
            )}
            {streaming && streamContent && (
              <div className="flex justify-start">
                <div className="w-full px-4 py-3 rounded-xl rounded-bl-sm bg-bg-raised border border-border text-text-primary text-sm leading-relaxed shadow-sm relative">
                  <MarkdownRenderer content={streamContent} />
                  <span className="inline-block w-1.5 h-4 ml-0.5 bg-accent rounded-sm" style={{ animation: 'cursor-blink 1s step-end infinite' }} />
                </div>
              </div>
            )}
            {/* Mobile: Go to manga button */}
            {onGoToManga && messages.length > 0 && !streaming && (
              <div className="flex justify-center py-3">
                <button
                  onClick={onGoToManga}
                  className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded-md
                         bg-accent-secondary/10 hover:bg-accent-secondary/10/60 text-accent-secondary border border-accent-secondary/20
                         transition-colors"
                >
                  <Image size={14} />
                  查看漫画 / 生成分镜
                </button>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* Input */}
          <div className="px-3 py-3 border-t border-border">
            <div className="mb-2 flex justify-end">
              <ModelSwitcher
                capability="llm"
                selectedModel={selectedLlmModel}
                onSelect={setSelectedLlmModel}
                disabled={streaming || isImportLocked}
              />
            </div>
            <div className="flex items-end gap-2 bg-bg-surface rounded-lg px-3 py-2 border border-border focus-within:border-accent transition-colors">
              <textarea
                ref={textareaRef}
                value={input}
                onChange={(e) => {
                  setInput(e.target.value);
                  autoResize(e.target);
                }}
                onKeyDown={handleKeyDown}
                placeholder="描述你的小说想法…"
                disabled={isImportLocked}
                rows={1}
                className="flex-1 bg-transparent text-sm text-text-primary placeholder-text-muted resize-none outline-none disabled:opacity-50"
                style={{ maxHeight: '160px', overflow: 'auto' }}
              />
              {streaming ? (
                <button
                  onClick={handleAbort}
                  className="p-2 rounded-md bg-accent hover:bg-accent-hover text-white transition-colors shrink-0"
                  title="停止生成"
                >
                  <Square size={16} />
                </button>
              ) : (
                <button
                  onClick={handleSend}
                  disabled={!input.trim() || isImportLocked}
                  className="p-2 rounded-md bg-accent hover:bg-accent-hover text-white disabled:opacity-30
                         disabled:cursor-not-allowed transition-colors shrink-0"
                >
                  <Send size={16} />
                </button>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
