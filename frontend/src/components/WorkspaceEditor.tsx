import { BookOpenCheck, ChevronLeft, ChevronRight, Image, MessageSquare, Plus, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import ChatPanel from './ChatPanel';
import MangaPanel from './MangaPanel';
import KnowledgeBasePage from './KnowledgeBasePage';
import { createNextChapter, deleteChapter, getChapter, listChapters, type Chapter } from '../api';

type MobileTab = 'chat' | 'manga';

interface Props {
  storyId: number;
  onBack: () => void;
}

export default function WorkspaceEditor({ storyId, onBack }: Props) {
  const [currentIdx, setCurrentIdx] = useState(0);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [currentChapter, setCurrentChapter] = useState<Chapter | null>(null);
  const [creatingChapter, setCreatingChapter] = useState(false);
  const [mobileTab, setMobileTab] = useState<MobileTab>('chat');
  const [loading, setLoading] = useState(true);
  const [showKnowledge, setShowKnowledge] = useState(false);

  const loadChapters = useCallback(async () => {
    setLoading(true);
    try {
      const chs = await listChapters(storyId);
      setChapters(chs);
      const savedIdx = Number(localStorage.getItem('lorevista.currentChapterIdx') || '0');
      const idx = chs.length > 0 ? Math.min(savedIdx, chs.length - 1) : 0;
      setCurrentIdx(idx);
      if (chs.length > 0) {
        const ch = await getChapter(chs[idx].id);
        setCurrentChapter(ch);
        localStorage.setItem('lorevista.currentChapterId', String(chs[idx].id));
      } else {
        setCurrentChapter(null);
      }
    } finally {
      setLoading(false);
    }
  }, [storyId]);

  useEffect(() => {
    void loadChapters();
    setMobileTab('chat');
  }, [loadChapters]);

  const refreshCurrentChapter = async () => {
    if (!currentChapter) return;
    setCurrentChapter(await getChapter(currentChapter.id));
  };

  const handleChapterRefresh = async (chapterId: number) => {
    try {
      const ch = await getChapter(chapterId);
      setCurrentChapter(ch);
      const chs = await listChapters(storyId);
      setChapters(chs);
    } catch {
      return;
    }
  };

  const setChapterByIndex = async (idx: number) => {
    if (idx < 0 || idx >= chapters.length) return;
    setCurrentIdx(idx);
    const ch = await getChapter(chapters[idx].id);
    setCurrentChapter(ch);
    localStorage.setItem('lorevista.currentChapterId', String(chapters[idx].id));
    localStorage.setItem('lorevista.currentChapterIdx', String(idx));
  };

  const handlePrevChapter = () => {
    if (currentIdx > 0) setChapterByIndex(currentIdx - 1);
  };

  const handleNextChapter = async () => {
    if (currentIdx < chapters.length - 1) {
      setChapterByIndex(currentIdx + 1);
      return;
    }
    setCreatingChapter(true);
    try {
      await createNextChapter(storyId);
      const chs = await listChapters(storyId);
      setChapters(chs);
      const idx = chs.length - 1;
      setCurrentIdx(idx);
      setCurrentChapter(await getChapter(chs[idx].id));
      localStorage.setItem('lorevista.currentChapterId', String(chs[idx].id));
      localStorage.setItem('lorevista.currentChapterIdx', String(idx));
    } catch (e: any) {
      alert('操作失败：' + e.message);
    } finally {
      setCreatingChapter(false);
    }
  };

  const handleDelete = async () => {
    if (!currentChapter || chapters.length <= 1) return;
    if (!confirm('确定删除这一章吗？')) return;
    try {
      await deleteChapter(currentChapter.id);
      const chs = await listChapters(storyId);
      setChapters(chs);
      const idx = Math.min(currentIdx, chs.length - 1);
      setCurrentIdx(idx);
      if (chs.length > 0) setCurrentChapter(await getChapter(chs[idx].id));
      else setCurrentChapter(null);
    } catch (e: any) {
      alert('操作失败：' + e.message);
    }
  };

  if (loading) {
    return <div className="flex flex-1 items-center justify-center bg-paper-base"><div className="h-8 w-8 animate-spin rounded-full border-2 border-paper-border border-t-vermilion" /></div>;
  }

  if (showKnowledge) {
    return <KnowledgeBasePage storyId={storyId} chapterNumber={currentChapter?.chapter_number} onBack={() => setShowKnowledge(false)} />;
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-paper-border bg-paper-surface/90 px-3 backdrop-blur-md">
        <button onClick={onBack} className="flex items-center gap-1.5 text-sm text-sumi-dim hover:text-vermilion transition-colors">
          <ChevronLeft size={16} />
          返回故事列表
        </button>
        <div className="flex items-center gap-2">
          <button onClick={() => setShowKnowledge(true)} className="rounded-md border border-paper-border bg-paper-base p-1.5 text-sumi-dim hover:text-vermilion" title="知识库"><BookOpenCheck size={15} /></button>
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

      <div className="flex border-b border-paper-border bg-paper-surface md:hidden">
        <button onClick={() => setMobileTab('chat')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-colors ' + (mobileTab === 'chat' ? 'border-b-2 border-vermilion text-vermilion' : 'text-sumi-dim hover:text-sumi')}>
          <MessageSquare size={14} />
          对话创作
        </button>
        <button onClick={() => setMobileTab('manga')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-colors ' + (mobileTab === 'manga' ? 'border-b-2 border-vermilion text-vermilion' : 'text-sumi-dim hover:text-sumi')}>
          <Image size={14} />
          漫画分镜
        </button>
      </div>

      <div className="shrink-0 overflow-x-auto border-b border-paper-border bg-paper-surface px-2 py-2 md:hidden">
        <div className="flex gap-1">
          {chapters.map((ch: Chapter, idx: number) => (
            <button key={ch.id} onClick={() => setChapterByIndex(idx)} className={'shrink-0 rounded-full border px-3 py-1.5 text-xs transition-all duration-200 ' + (ch.id === currentChapter?.id ? 'border-vermilion bg-vermilion-light/50 text-vermilion font-medium' : 'border-paper-border bg-paper-base text-sumi-dim hover:text-sumi')}>
              第 {ch.chapter_number} 章
            </button>
          ))}
        </div>
      </div>

      <main className="min-h-0 flex-1 md:flex">
        <div className={'h-full md:w-1/2 md:border-r md:border-paper-border ' + (mobileTab === 'chat' ? '' : 'hidden md:block')}>
          <ChatPanel
            chapter={currentChapter}
            onMessageSent={refreshCurrentChapter}
            onChapterRefresh={handleChapterRefresh}
            onGoToManga={() => setMobileTab('manga')}
          />
        </div>
        <div className={'h-full md:w-1/2 ' + (mobileTab === 'manga' ? '' : 'hidden md:block')}>
          <MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh} />
        </div>
      </main>

      <footer className="flex h-14 shrink-0 items-center justify-center gap-2 border-t border-paper-border bg-paper-surface/90 px-2 backdrop-blur-md md:gap-4">
        <button onClick={handlePrevChapter} disabled={currentIdx === 0} className="flex items-center gap-1 rounded-md border border-paper-border bg-paper-base px-3 py-2 text-sm font-medium text-sumi-dim disabled:cursor-not-allowed disabled:opacity-30 hover:border-sumi-faint hover:text-sumi transition-colors">
          <ChevronLeft size={16} />
          <span className="hidden md:inline">上一章</span>
        </button>
        <button onClick={handleDelete} disabled={!currentChapter || chapters.length <= 1} className="flex items-center gap-1.5 rounded-md border border-vermilion/20 bg-vermilion-light/20 px-3 py-2 text-sm font-medium text-vermilion disabled:cursor-not-allowed disabled:opacity-30 hover:bg-vermilion-light/40 transition-colors" aria-label="Delete chapter">
          <Trash2 size={14} />
        </button>
        <div className="flex items-center gap-1">
          {chapters.map((ch: Chapter, i: number) => (
            <button key={ch.id} onClick={() => setChapterByIndex(i)} className={'h-2 w-2 rounded-full transition-all duration-200 ' + (i === currentIdx ? 'bg-vermilion scale-125' : 'bg-sumi-faint/30 hover:bg-sumi-faint/60')} aria-label={'切换到第 ' + ch.chapter_number + ' 章'} />
          ))}
        </div>
        <button onClick={handleNextChapter} disabled={creatingChapter} className="flex items-center gap-1 rounded-md bg-vermilion px-3 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40 hover:bg-vermilion-hover transition-colors md:px-5">
          {currentIdx === chapters.length - 1 ? (<><Plus size={16} />{creatingChapter ? '创建中…' : '新建'}</>) : (<><span className="hidden md:inline">下一章</span><ChevronRight size={16} /></>)}
        </button>
      </footer>
    </div>
  );
}
