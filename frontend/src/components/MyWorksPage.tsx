import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown, ArrowUp, ArrowUpDown, BookOpenText, CalendarDays, CheckCircle2,
  ChevronLeft, ChevronRight, Eye, FileText, Globe, Image as ImageIcon, Layers,
  Loader2, RefreshCw, Search, Send, SquarePen,
} from 'lucide-react';
import {
  getChapter, listMyWorks, mangaImageUrl, publishStory, refImageUrl, updateChapterOrder,
  type Chapter, type MangaImage, type MyWork, type MyWorkChapter,
} from '../api';

type Format = 'novel' | 'manga';
type PageView = 'list' | 'detail' | 'reader';
type StatusFilter = 'all' | 'published' | 'draft';
type SortOrder = 'newest' | 'oldest';

const formatCopy = {
  novel: {
    label: '小说管理',
    subtitle: '管理小说正文、章节顺序与独立发布状态',
    chapterUnit: '章',
    contentMetric: '正文总字数',
    publishedMetric: '上线章节',
    emptyContent: '该章节还没有小说正文',
  },
  manga: {
    label: '漫画管理',
    subtitle: '管理漫画页面、章节顺序与独立发布状态',
    chapterUnit: '话',
    contentMetric: '漫画总页数',
    publishedMetric: '上线章节',
    emptyContent: '该章节还没有生成漫画',
  },
} as const;

const isWorkPublished = (work: MyWork, format: Format) =>
  format === 'novel' ? work.novel_is_published : work.manga_is_published;

const isChapterPublished = (chapter: MyWorkChapter, format: Format) =>
  format === 'novel' ? chapter.novel_is_published : chapter.manga_is_published;

const chapterContentCount = (chapter: MyWorkChapter, format: Format) =>
  format === 'novel' ? chapter.novel_char_count : chapter.manga_image_count;

const chapterTitle = (chapter: MyWorkChapter, format: Format) =>
  chapter.display_title || `第 ${chapter.chapter_number} ${formatCopy[format].chapterUnit}`;

const formatDate = (date: string | null) => date ? new Date(date).toLocaleDateString('zh-CN') : '未记录';

export default function MyWorksPage() {
  const [format, setFormat] = useState<Format>('novel');
  const [view, setView] = useState<PageView>('list');
  const [works, setWorks] = useState<MyWork[]>([]);
  const [selectedWork, setSelectedWork] = useState<MyWork | null>(null);
  const [editChapters, setEditChapters] = useState<MyWorkChapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('newest');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [readerChapterId, setReaderChapterId] = useState<number | null>(null);
  const [readerChapter, setReaderChapter] = useState<Chapter | null>(null);
  const [readerImages, setReaderImages] = useState<MangaImage[]>([]);
  const [readerLoading, setReaderLoading] = useState(false);
  const pageScrollRef = useRef<HTMLDivElement>(null);

  const loadWorks = useCallback(async () => {
    setLoadError('');
    try {
      setWorks(await listMyWorks());
    } catch (error) {
      console.error(error);
      setLoadError('作品加载失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadWorks(); }, [loadWorks]);

  const switchFormat = (next: Format) => {
    setFormat(next);
    setView('list');
    setSelectedWork(null);
    setStatusFilter('all');
    setReaderChapter(null);
    setReaderImages([]);
  };

  const stats = useMemo(() => {
    const publishedWorks = works.filter((work) => isWorkPublished(work, format)).length;
    const totalChapters = works.reduce((sum, work) => sum + work.chapters.length, 0);
    const publishedChapters = works.reduce(
      (sum, work) => sum + work.chapters.filter((chapter) => isChapterPublished(chapter, format)).length, 0,
    );
    const contentCount = works.reduce(
      (sum, work) => sum + work.chapters.reduce((chapterSum, chapter) => chapterSum + chapterContentCount(chapter, format), 0), 0,
    );
    return { publishedWorks, totalChapters, publishedChapters, contentCount };
  }, [format, works]);

  const filteredWorks = useMemo(() => works
    .filter((work) => !searchQuery || work.title.toLowerCase().includes(searchQuery.toLowerCase()))
    .filter((work) => statusFilter === 'all'
      || (statusFilter === 'published' && isWorkPublished(work, format))
      || (statusFilter === 'draft' && !isWorkPublished(work, format)))
    .sort((a, b) => sortOrder === 'newest'
      ? (b.created_at || '').localeCompare(a.created_at || '')
      : (a.created_at || '').localeCompare(b.created_at || '')),
  [format, searchQuery, sortOrder, statusFilter, works]);

  const openDetail = (work: MyWork) => {
    setSelectedWork(work);
    setEditChapters(work.chapters
      .slice()
      .sort((a, b) => a.display_order - b.display_order || a.chapter_number - b.chapter_number)
      .map((chapter) => ({ ...chapter, is_published: isChapterPublished(chapter, format) })));
    setView('detail');
    requestAnimationFrame(() => pageScrollRef.current?.scrollTo({ top: 0 }));
  };

  const backToList = async () => {
    setView('list');
    setSelectedWork(null);
    await loadWorks();
  };

  const moveChapter = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= editChapters.length) return;
    setEditChapters((current) => {
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const handleSave = async () => {
    if (!selectedWork) return;
    setSaving(true);
    try {
      const publishedIds = editChapters.filter((chapter) => chapter.is_published).map((chapter) => chapter.id);
      await publishStory(selectedWork.id, format, publishedIds.length > 0, publishedIds);
      await updateChapterOrder(selectedWork.id, editChapters.map((chapter, index) => ({
        chapter_id: chapter.id,
        display_order: index,
        display_title: chapter.display_title || undefined,
      })));
      const updated = await listMyWorks();
      setWorks(updated);
      const work = updated.find((item) => item.id === selectedWork.id);
      if (work) openDetail(work);
    } catch (error) {
      alert(`保存失败：${error instanceof Error ? error.message : '未知错误'}`);
    } finally {
      setSaving(false);
    }
  };

  const openReader = async (chapterId: number) => {
    setReaderChapterId(chapterId);
    setReaderChapter(null);
    setReaderImages([]);
    setReaderLoading(true);
    setView('reader');
    try {
      const chapter = await getChapter(chapterId);
      setReaderChapter(chapter);
      setReaderImages((chapter.images || []).slice().sort((a, b) => a.image_number - b.image_number));
    } catch (error) {
      console.error(error);
    } finally {
      setReaderLoading(false);
    }
  };

  const navigateReader = (direction: -1 | 1) => {
    if (!selectedWork) return;
    const index = selectedWork.chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const next = selectedWork.chapters[index + direction];
    if (next) void openReader(next.id);
  };

  if (loading) return <div className="flex flex-1 items-center justify-center"><Loader2 size={28} className="animate-spin text-accent" /></div>;

  if (loadError) {
    return <div className="flex flex-1 items-center justify-center bg-bg-base px-4"><div className="text-center">
      <RefreshCw size={38} className="mx-auto text-text-muted" strokeWidth={1.35} />
      <p className="mt-3 text-sm font-medium text-text-primary">{loadError}</p>
      <button type="button" onClick={() => void loadWorks()} className="mt-4 inline-flex items-center gap-1.5 rounded-md border border-border bg-bg-raised px-3 py-2 text-sm text-text-secondary hover:border-accent/40 hover:text-accent"><RefreshCw size={14} />重新加载</button>
    </div></div>;
  }

  if (view === 'list') {
    const draftCount = works.length - stats.publishedWorks;
    return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <div className="shrink-0 border-b border-border bg-bg-raised">
        <div className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <div className="mb-3 inline-flex rounded-md bg-bg-surface p-1" aria-label="作品形态">
                {(['novel', 'manga'] as Format[]).map((item) => <button key={item} type="button" onClick={() => switchFormat(item)} className={`flex h-9 items-center gap-2 rounded px-4 text-sm font-semibold transition-colors ${format === item ? 'bg-bg-raised text-accent shadow-sm' : 'text-text-secondary hover:text-text-primary'}`}>
                  {item === 'novel' ? <BookOpenText size={16} /> : <ImageIcon size={16} />}{formatCopy[item].label}
                </button>)}
              </div>
              <h1 className="font-display text-2xl font-bold text-text-primary md:text-3xl">{formatCopy[format].label}</h1>
              <p className="mt-1.5 text-sm text-text-secondary">{formatCopy[format].subtitle}</p>
            </div>
            <div className="grid w-full grid-cols-[minmax(0,1fr)_128px] gap-2 lg:w-auto lg:grid-cols-[256px_144px]">
              <label className="relative min-w-0"><Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" /><input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder="搜索作品名称" className="h-10 w-full rounded-md border border-border bg-bg-base pl-9 pr-3 text-sm text-text-primary focus:border-accent focus:outline-none" /></label>
              <label className="relative min-w-0"><select value={sortOrder} onChange={(event) => setSortOrder(event.target.value as SortOrder)} className="h-10 w-full appearance-none rounded-md border border-border bg-bg-base py-2 pl-3 pr-9 text-sm text-text-secondary focus:border-accent focus:outline-none"><option value="newest">最近创建</option><option value="oldest">最早创建</option></select><ArrowUpDown size={13} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-text-muted" /></label>
            </div>
          </div>
          <div className="mt-6 grid grid-cols-2 border-y border-border sm:grid-cols-4">
            {[
              { label: '全部作品', value: works.length, icon: <Layers size={15} className="text-accent" /> },
              { label: '已发布作品', value: stats.publishedWorks, icon: <Globe size={15} className="text-success" /> },
              { label: formatCopy[format].contentMetric, value: stats.contentCount.toLocaleString('zh-CN'), icon: format === 'novel' ? <FileText size={15} className="text-accent-secondary" /> : <ImageIcon size={15} className="text-accent-secondary" /> },
              { label: formatCopy[format].publishedMetric, value: stats.publishedChapters, icon: <CheckCircle2 size={15} className="text-accent-tertiary" /> },
            ].map((stat, index) => <div key={stat.label} className={`flex items-center gap-3 py-3 ${index % 2 ? 'border-l border-border' : ''} ${index > 1 ? 'border-t border-border sm:border-t-0' : ''} sm:border-l sm:px-5 sm:first:border-l-0 sm:first:pl-0`}><span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-bg-surface">{stat.icon}</span><div><div className="text-xl font-semibold text-text-primary">{stat.value}</div><div className="text-[11px] text-text-muted">{stat.label}</div></div></div>)}
          </div>
        </div>
      </div>
      <div ref={pageScrollRef} className="flex-1 overflow-y-auto">
        <main className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="mb-5 flex items-center justify-between gap-3"><div className="flex gap-1 rounded-md bg-bg-surface p-1">
            {([['all', '全部作品', works.length], ['published', '已发布', stats.publishedWorks], ['draft', '草稿箱', draftCount]] as const).map(([key, label, count]) => <button key={key} type="button" onClick={() => setStatusFilter(key)} className={`h-8 rounded px-3 text-sm font-medium ${statusFilter === key ? 'bg-bg-raised text-text-primary shadow-sm' : 'text-text-secondary'}`}>{label} <span className="text-[11px] text-text-muted">{count}</span></button>)}
          </div><span className="hidden text-xs text-text-muted sm:inline">待发布作品 {draftCount} 部</span></div>
          {filteredWorks.length === 0 ? <div className="flex min-h-64 items-center justify-center border-y border-border text-center text-text-muted"><div><ImageIcon size={42} className="mx-auto" strokeWidth={1.25} /><p className="mt-3 text-sm">{searchQuery ? '没有匹配的作品' : '这里还没有作品'}</p></div></div>
            : <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">{filteredWorks.map((work) => {
              const published = isWorkPublished(work, format);
              const contentCount = work.chapters.reduce((sum, chapter) => sum + chapterContentCount(chapter, format), 0);
              const publishedCount = work.chapters.filter((chapter) => isChapterPublished(chapter, format)).length;
              const cover = work.cover_image ? refImageUrl(work.cover_image) : null;
              return <button key={work.id} type="button" onClick={() => openDetail(work)} className="group grid min-w-0 grid-cols-[82px_1fr] gap-3 rounded-md border border-border bg-bg-raised p-3 text-left transition-all hover:border-accent/30 hover:shadow-[0_10px_26px_rgba(24,27,25,0.07)] sm:grid-cols-[96px_1fr] sm:gap-4">
                <div className="aspect-[3/4] overflow-hidden rounded-md border border-border bg-bg-surface">{cover ? <img src={cover} alt={work.title} className="h-full w-full object-cover" loading="lazy" /> : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={30} className="text-text-muted" /></div>}</div>
                <div className="flex min-w-0 flex-col py-0.5"><div className="flex items-start justify-between gap-2"><div className="min-w-0"><h2 className="truncate text-base font-semibold text-text-primary group-hover:text-accent">{work.title}</h2><p className="mt-1 line-clamp-2 text-xs leading-[18px] text-text-secondary">{work.description || '暂无简介'}</p></div><span className={`shrink-0 rounded-sm px-2 py-1 text-[11px] font-medium ${published ? 'bg-success/10 text-success' : 'bg-bg-surface text-text-secondary'}`}>{published ? '已发布' : '草稿'}</span></div>
                  <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1 pt-3 text-xs text-text-muted"><span>{work.chapters.length} {formatCopy[format].chapterUnit}</span><span>{format === 'novel' ? `${contentCount.toLocaleString('zh-CN')} 字` : `${contentCount} 页`}</span><span>上线 {publishedCount}</span><span className="ml-auto">{formatDate(work.created_at)}</span></div>
                </div>
              </button>;
            })}</div>}
        </main>
      </div>
    </div>;
  }

  if (view === 'detail' && selectedWork) {
    const published = isWorkPublished(selectedWork, format);
    const cover = selectedWork.cover_image ? refImageUrl(selectedWork.cover_image) : null;
    const publishedCount = editChapters.filter((chapter) => chapter.is_published).length;
    return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-bg-raised px-4 md:px-8"><button type="button" onClick={() => void backToList()} className="flex items-center gap-2 text-sm text-text-secondary hover:text-text-primary"><ChevronLeft size={18} />返回{formatCopy[format].label}</button><button type="button" onClick={() => void handleSave()} disabled={saving} className="flex h-10 items-center gap-2 rounded-md bg-accent px-4 text-sm font-semibold text-white hover:bg-accent-hover disabled:opacity-40">{saving ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}保存发布设置</button></header>
      <div ref={pageScrollRef} className="flex-1 overflow-y-auto"><main className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8">
        <section className="grid gap-5 border-b border-border pb-6 sm:grid-cols-[112px_1fr]">
          <div className="aspect-[3/4] overflow-hidden rounded-md border border-border bg-bg-surface">{cover ? <img src={cover} alt={selectedWork.title} className="h-full w-full object-cover" /> : <div className="flex h-full items-center justify-center"><ImageIcon size={34} className="text-text-muted" /></div>}</div>
          <div className="min-w-0"><div className="flex flex-wrap items-center gap-3 text-xs"><span className={`inline-flex items-center gap-1.5 rounded-sm px-2 py-1 font-medium ${published ? 'bg-success/10 text-success' : 'bg-bg-surface text-text-secondary'}`}><span className={`h-1.5 w-1.5 rounded-full ${published ? 'bg-success' : 'bg-bg-base-faint'}`} />{published ? `${formatCopy[format].label.replace('管理', '')}已公开` : `${formatCopy[format].label.replace('管理', '')}未公开`}</span><span className="flex items-center gap-1.5 text-text-muted"><CalendarDays size={13} />创建于 {formatDate(selectedWork.created_at)}</span></div><h1 className="font-display mt-3 break-words text-2xl font-bold text-text-primary md:text-3xl">{selectedWork.title}</h1><p className="mt-2 max-w-2xl text-sm leading-6 text-text-secondary">{selectedWork.description || '暂无简介'}</p></div>
        </section>
        <section className="py-6"><div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between"><div><h2 className="text-base font-semibold text-text-primary">章节发布队列</h2><p className="mt-1 text-xs text-text-muted">调整展示标题、顺序和当前形态的读者可见状态</p></div><div className="flex items-center gap-2"><span className="text-xs text-text-muted">已上线 {publishedCount} / {editChapters.length}</span><button type="button" onClick={() => setEditChapters((items) => items.map((item) => ({ ...item, is_published: true })))} className="rounded-md border border-border px-2.5 py-1.5 text-xs text-text-secondary hover:text-accent">全部上线</button><button type="button" onClick={() => setEditChapters((items) => items.map((item) => ({ ...item, is_published: false })))} className="rounded-md border border-border px-2.5 py-1.5 text-xs text-text-secondary hover:text-accent">全部转草稿</button></div></div>
          <div className="divide-y divide-paper-border border-y border-border">{editChapters.map((chapter, index) => <div key={chapter.id} className="grid grid-cols-[auto_1fr_auto] items-center gap-3 py-3 md:grid-cols-[70px_minmax(180px,1fr)_140px_100px_44px]">
            <div className="flex items-center gap-1"><button type="button" onClick={() => moveChapter(index, -1)} disabled={index === 0} className="flex h-7 w-7 items-center justify-center text-text-muted hover:text-text-primary disabled:opacity-20" title="上移"><ArrowUp size={14} /></button><button type="button" onClick={() => moveChapter(index, 1)} disabled={index === editChapters.length - 1} className="flex h-7 w-7 items-center justify-center text-text-muted hover:text-text-primary disabled:opacity-20" title="下移"><ArrowDown size={14} /></button></div>
            <label className="relative min-w-0"><SquarePen size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" /><input value={chapter.display_title || ''} onChange={(event) => setEditChapters((items) => items.map((item) => item.id === chapter.id ? { ...item, display_title: event.target.value } : item))} placeholder={`第 ${chapter.chapter_number} ${formatCopy[format].chapterUnit}`} className="h-9 w-full rounded-md border border-border bg-bg-raised pl-9 pr-3 text-sm text-text-primary focus:border-accent focus:outline-none" /></label>
            <span className="hidden text-xs text-text-muted md:block">{format === 'novel' ? `${chapter.novel_char_count.toLocaleString('zh-CN')} 字` : `${chapter.manga_image_count} 页漫画`}</span>
            <label className="flex cursor-pointer items-center justify-end gap-2 text-xs text-text-secondary"><input type="checkbox" checked={chapter.is_published} onChange={() => setEditChapters((items) => items.map((item) => item.id === chapter.id ? { ...item, is_published: !item.is_published } : item))} className="h-4 w-4 accent-vermilion" />{chapter.is_published ? '可见' : '草稿'}</label>
            <button type="button" onClick={() => void openReader(chapter.id)} className="flex h-9 w-9 items-center justify-center rounded-md text-text-muted hover:bg-accent-muted/40 hover:text-accent" title={`预览${chapterTitle(chapter, format)}`}><Eye size={16} /></button>
          </div>)}</div>
        </section>
      </main></div>
    </div>;
  }

  if (view === 'reader' && selectedWork) {
    const chapters = selectedWork.chapters.slice().sort((a, b) => a.display_order - b.display_order || a.chapter_number - b.chapter_number);
    const currentIndex = chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const currentMeta = chapters[currentIndex];
    return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border bg-bg-raised px-3 md:px-6"><button type="button" onClick={() => setView('detail')} className="flex min-w-0 items-center gap-2 text-sm text-text-secondary hover:text-text-primary"><ChevronLeft size={18} /><span className="hidden sm:inline">返回管理</span><span className="hidden text-text-muted sm:inline">/</span><span className="truncate font-semibold text-text-primary">{selectedWork.title}</span></button><select value={readerChapterId ?? ''} onChange={(event) => void openReader(Number(event.target.value))} aria-label="切换章节" className="h-9 max-w-48 rounded-md border border-border bg-bg-raised px-2 text-xs text-text-secondary">{chapters.map((chapter) => <option key={chapter.id} value={chapter.id}>{chapterTitle(chapter, format)}</option>)}</select></header>
      <div className="flex-1 overflow-y-auto bg-bg-surface/60">{readerLoading ? <div className="flex h-full items-center justify-center"><Loader2 size={28} className="animate-spin text-accent" /></div>
        : format === 'novel' ? <article className="mx-auto min-h-full max-w-3xl bg-bg-raised px-6 py-10 shadow-[0_0_40px_rgba(24,27,25,0.06)] sm:px-12 md:py-14"><div className="mb-8 border-b border-border pb-5"><div className="text-xs font-semibold text-accent">小说预览</div><h1 className="font-display mt-2 text-2xl font-bold text-text-primary">{currentMeta ? chapterTitle(currentMeta, format) : ''}</h1></div>{readerChapter?.novel_content?.trim() ? <div className="whitespace-pre-wrap break-words text-[16px] leading-8 text-text-primary">{readerChapter.novel_content}</div> : <EmptyReader message={formatCopy.novel.emptyContent} />}</article>
          : readerImages.length ? <div className="mx-auto max-w-3xl bg-bg-raised shadow-[0_0_40px_rgba(24,27,25,0.08)]">{readerImages.map((image) => <img key={image.id} src={mangaImageUrl(image.image_path) || ''} alt={`漫画第 ${image.image_number} 页`} className="block w-full" loading="lazy" />)}</div> : <EmptyReader message={formatCopy.manga.emptyContent} />}</div>
      <footer className="flex shrink-0 items-center justify-center gap-5 border-t border-border bg-bg-raised px-3 py-3"><button type="button" onClick={() => navigateReader(-1)} disabled={currentIndex <= 0} className="flex h-9 items-center gap-1 rounded-md border border-border px-3 text-sm text-text-secondary disabled:opacity-30"><ChevronLeft size={16} />上一{formatCopy[format].chapterUnit}</button><span className="min-w-14 text-center text-xs text-text-muted">{currentIndex + 1} / {chapters.length}</span><button type="button" onClick={() => navigateReader(1)} disabled={currentIndex < 0 || currentIndex >= chapters.length - 1} className="flex h-9 items-center gap-1 rounded-md border border-border px-3 text-sm text-text-secondary disabled:opacity-30">下一{formatCopy[format].chapterUnit}<ChevronRight size={16} /></button></footer>
    </div>;
  }

  return null;
}

function EmptyReader({ message }: { message: string }) {
  return <div className="flex min-h-80 items-center justify-center px-4 text-center text-text-muted"><div><FileText size={48} className="mx-auto mb-4" strokeWidth={1.1} /><p className="text-sm">{message}</p></div></div>;
}
