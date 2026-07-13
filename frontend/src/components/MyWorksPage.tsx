import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown,
  ArrowRight,
  ArrowUp,
  ArrowUpDown,
  BookOpenText,
  CalendarDays,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Eye,
  FileText,
  Globe,
  Image as ImageIcon,
  Layers,
  Loader2,
  RefreshCw,
  Search,
  Send,
  SquarePen,
} from 'lucide-react';
import {
  listMyWorks, publishStory, updateChapterOrder, getChapter,
  mangaImageUrl, refImageUrl,
  type MyWork, type MyWorkChapter, type Chapter, type MangaImage,
} from '../api';

type View = 'list' | 'detail' | 'reader';
type StatusFilter = 'all' | 'published' | 'draft';
type SortOrder = 'newest' | 'oldest';

interface WorkStats {
  totalChapters: number;
  publishedChapters: number;
  draftChapters: number;
  progress: number;
}

const text = {
  allWorks: '\u5168\u90e8\u4f5c\u54c1',
  published: '\u5df2\u53d1\u5e03',
  draftBox: '\u8349\u7a3f\u7bb1',
  unrecorded: '\u672a\u8bb0\u5f55',
  title: '\u4f5c\u54c1\u7ba1\u7406',
  subtitle: '\u7ba1\u7406\u53d1\u5e03\u72b6\u6001\u3001\u7ae0\u8282\u987a\u5e8f\u4e0e\u8bfb\u8005\u53ef\u89c1\u5185\u5bb9',
  search: '\u641c\u7d22\u4f5c\u54c1\u540d\u79f0',
  newest: '\u6700\u8fd1\u521b\u5efa',
  oldest: '\u6700\u65e9\u521b\u5efa',
  publishedWorks: '\u5df2\u53d1\u5e03\u4f5c\u54c1',
  totalChapters: '\u7ae0\u8282\u603b\u6570',
  onlineChapters: '\u4e0a\u7ebf\u7ae0\u8282',
  pendingWorks: '\u5f85\u53d1\u5e03\u4f5c\u54c1',
  worksUnit: '\u90e8',
  noMatch: '\u6ca1\u6709\u5339\u914d\u7684\u4f5c\u54c1',
  noWorks: '\u8fd8\u6ca1\u6709\u4f5c\u54c1\uff0c\u53bb\u5de5\u4f5c\u533a\u521b\u5efa\u5427',
  noDescription: '\u6682\u65e0\u7b80\u4ecb',
  draft: '\u8349\u7a3f',
  totalPrefix: '\u5171',
  chapterUnit: '\u8bdd',
  online: '\u4e0a\u7ebf',
  createdAt: '\u521b\u5efa\u4e8e',
  backToList: '\u8fd4\u56de\u4f5c\u54c1\u5217\u8868',
  savePublish: '\u4fdd\u5b58\u53d1\u5e03\u8bbe\u7f6e',
  saveFailed: '\u4fdd\u5b58\u5931\u8d25\uff1a',
  unknownError: '\u672a\u77e5\u9519\u8bef',
  publicWork: '\u4f5c\u54c1\u5df2\u516c\u5f00',
  privateWork: '\u4f5c\u54c1\u672a\u516c\u5f00',
  publishProgress: '\u53d1\u5e03\u8fdb\u5ea6',
  publishedChapterCount: '\u5df2\u4e0a\u7ebf',
  pendingChapterCount: '\u5f85\u6574\u7406',
  chapterQueue: '\u7ae0\u8282\u53d1\u5e03\u961f\u5217',
  queueSubtitle: '\u8c03\u6574\u5c55\u793a\u6807\u9898\u3001\u6392\u5e8f\u548c\u8bfb\u8005\u53ef\u89c1\u72b6\u6001',
  allOnline: '\u5168\u90e8\u4e0a\u7ebf',
  allDraft: '\u5168\u90e8\u8f6c\u8349\u7a3f',
  noChapters: '\u6682\u65e0\u7ae0\u8282\uff0c\u53bb\u5de5\u4f5c\u533a\u521b\u5efa\u5427',
  chapterPrefix: '\u7b2c',
  visible: '\u53ef\u89c1',
  preview: '\u9884\u89c8',
  back: '\u8fd4\u56de',
  noManga: '\u8be5\u8bdd\u6682\u672a\u751f\u6210\u6f2b\u753b',
  backToManage: '\u8fd4\u56de\u7ba1\u7406',
  pageUnit: '\u9875',
  prevChapter: '\u4e0a\u4e00\u8bdd',
  nextChapter: '\u4e0b\u4e00\u8bdd',
  retry: '\u91cd\u65b0\u52a0\u8f7d',
  loadFailed: '\u4f5c\u54c1\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5',
  manage: '\u7ba1\u7406',
  onlineRate: '\u7ae0\u8282\u4e0a\u7ebf\u7387',
};

const statusFilters: { key: StatusFilter; label: string }[] = [
  { key: 'all', label: text.allWorks },
  { key: 'published', label: text.published },
  { key: 'draft', label: text.draftBox },
];

const chapterTitle = (chapterNumber: number) => `${text.chapterPrefix} ${chapterNumber} ${text.chapterUnit}`;

const getWorkStats = (work: MyWork): WorkStats => {
  const totalChapters = work.chapters.length;
  const publishedChapters = work.chapters.filter((chapter) => chapter.is_published).length;
  const draftChapters = Math.max(totalChapters - publishedChapters, 0);
  const progress = totalChapters > 0 ? Math.round((publishedChapters / totalChapters) * 100) : 0;
  return { totalChapters, publishedChapters, draftChapters, progress };
};

const formatDate = (date: string | null) => date ? new Date(date).toLocaleDateString('zh-CN') : text.unrecorded;

export default function MyWorksPage() {
  const [works, setWorks] = useState<MyWork[]>([]);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<View>('list');
  const [selectedWork, setSelectedWork] = useState<MyWork | null>(null);
  const [editChapters, setEditChapters] = useState<MyWorkChapter[]>([]);
  const [saving, setSaving] = useState(false);
  const [readerChapterId, setReaderChapterId] = useState<number | null>(null);
  const [readerImages, setReaderImages] = useState<MangaImage[]>([]);
  const [readerLoading, setReaderLoading] = useState(false);
  const [readerChapterInfo, setReaderChapterInfo] = useState<{ chapter_number: number; display_title: string } | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('newest');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [loadError, setLoadError] = useState('');
  const pageScrollRef = useRef<HTMLDivElement>(null);

  const loadWorks = useCallback(async () => {
    setLoadError('');
    try {
      const data = await listMyWorks();
      setWorks(data);
    } catch (error) {
      console.error(error);
      setLoadError(text.loadFailed);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void listMyWorks()
      .then((data) => {
        if (!cancelled) setWorks(data);
      })
      .catch((error) => {
        console.error(error);
        if (!cancelled) setLoadError(text.loadFailed);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  const dashboardStats = useMemo(() => {
    const publishedWorks = works.filter((work) => work.is_published).length;
    const totalChapters = works.reduce((sum, work) => sum + work.chapters.length, 0);
    const publishedChapters = works.reduce((sum, work) => sum + getWorkStats(work).publishedChapters, 0);
    const pendingWorks = works.length - publishedWorks;
    return { publishedWorks, totalChapters, publishedChapters, pendingWorks };
  }, [works]);

  const filteredWorks = useMemo(() => works
    .filter((work) => {
      const matchesSearch = !searchQuery || work.title.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesStatus = statusFilter === 'all'
        || (statusFilter === 'published' && work.is_published)
        || (statusFilter === 'draft' && !work.is_published);
      return matchesSearch && matchesStatus;
    })
    .sort((a, b) => {
      const dateA = a.created_at || '';
      const dateB = b.created_at || '';
      return sortOrder === 'newest' ? dateB.localeCompare(dateA) : dateA.localeCompare(dateB);
    }), [searchQuery, sortOrder, statusFilter, works]);

  const coverUrl = (work: MyWork) => work.cover_image ? refImageUrl(work.cover_image) : null;

  const openDetail = (work: MyWork) => {
    setSelectedWork(work);
    setEditChapters(work.chapters.map((chapter) => ({ ...chapter })));
    setView('detail');
    requestAnimationFrame(() => pageScrollRef.current?.scrollTo({ top: 0 }));
  };

  const backToList = async () => {
    setView('list');
    setSelectedWork(null);
    requestAnimationFrame(() => pageScrollRef.current?.scrollTo({ top: 0 }));
    await loadWorks();
  };

  const toggleChapter = (chapterId: number) => {
    setEditChapters((prev) => prev.map((chapter) => chapter.id === chapterId ? { ...chapter, is_published: !chapter.is_published } : chapter));
  };

  const updateDisplayTitle = (chapterId: number, title: string) => {
    setEditChapters((prev) => prev.map((chapter) => chapter.id === chapterId ? { ...chapter, display_title: title } : chapter));
  };

  const moveChapter = (index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= editChapters.length) return;
    const nextChapters = [...editChapters];
    [nextChapters[index], nextChapters[targetIndex]] = [nextChapters[targetIndex], nextChapters[index]];
    setEditChapters(nextChapters);
  };

  const selectAll = () => setEditChapters((prev) => prev.map((chapter) => ({ ...chapter, is_published: true })));
  const deselectAll = () => setEditChapters((prev) => prev.map((chapter) => ({ ...chapter, is_published: false })));

  const handleSave = async () => {
    if (!selectedWork) return;
    setSaving(true);
    try {
      const selectedChapterIds = editChapters.filter((chapter) => chapter.is_published).map((chapter) => chapter.id);
      await publishStory(selectedWork.id, selectedChapterIds.length > 0, selectedChapterIds);
      await updateChapterOrder(selectedWork.id, editChapters.map((chapter, index) => ({
        chapter_id: chapter.id,
        display_order: index,
        display_title: chapter.display_title || undefined,
      })));
      const updatedWorks = await listMyWorks();
      setWorks(updatedWorks);
      const updatedWork = updatedWorks.find((work) => work.id === selectedWork.id);
      if (updatedWork) {
        setSelectedWork(updatedWork);
        setEditChapters(updatedWork.chapters.map((chapter) => ({ ...chapter })));
      }
    } catch (error) {
      alert(text.saveFailed + (error instanceof Error ? error.message : text.unknownError));
    } finally {
      setSaving(false);
    }
  };

  const openReader = async (chapterId: number) => {
    setReaderChapterId(chapterId);
    setReaderImages([]);
    setReaderLoading(true);
    setView('reader');
    try {
      const chapter: Chapter = await getChapter(chapterId);
      const chapterMeta = selectedWork?.chapters.find((item) => item.id === chapterId);
      setReaderChapterInfo({
        chapter_number: chapter.chapter_number,
        display_title: chapterMeta?.display_title || chapterTitle(chapter.chapter_number),
      });
      setReaderImages((chapter.images || []).sort((a, b) => a.image_number - b.image_number));
    } catch (error) {
      console.error(error);
      setReaderImages([]);
    } finally {
      setReaderLoading(false);
    }
  };

  const closeReader = () => {
    setView('detail');
    setReaderChapterId(null);
    setReaderImages([]);
  };

  const navigateReaderChapter = (direction: -1 | 1) => {
    if (!selectedWork) return;
    const chapterIndex = selectedWork.chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const nextIndex = chapterIndex + direction;
    if (nextIndex >= 0 && nextIndex < selectedWork.chapters.length) {
      openReader(selectedWork.chapters[nextIndex].id);
    }
  };

  const currentReaderIdx = selectedWork?.chapters.findIndex((chapter) => chapter.id === readerChapterId) ?? -1;

  if (loading) {
    return <div className="flex-1 flex items-center justify-center"><Loader2 size={28} className="animate-spin text-coral" /></div>;
  }

  if (loadError) {
    return (
      <div className="flex flex-1 items-center justify-center bg-paper-base px-4">
        <div className="text-center">
          <RefreshCw size={38} className="mx-auto text-sumi-faint" strokeWidth={1.35} />
          <p className="mt-3 text-sm font-medium text-sumi">{loadError}</p>
          <button type="button" onClick={() => void loadWorks()} className="mt-4 inline-flex items-center gap-1.5 rounded-md border border-paper-border bg-paper-raised px-3 py-2 text-sm text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion">
            <RefreshCw size={14} />{text.retry}
          </button>
        </div>
      </div>
    );
  }

  if (view === 'list') {
    return (
      <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
        <div className="shrink-0 border-b border-paper-border bg-paper-raised">
          <div className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
            <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
              <div>
                <div className="flex items-center gap-2 text-xs font-semibold text-vermilion"><BookOpenText size={15} />创作中心</div>
                <h1 className="font-display mt-1.5 text-2xl font-bold text-sumi md:text-3xl">{text.title}</h1>
                <p className="mt-1.5 text-sm text-sumi-dim">{text.subtitle}</p>
              </div>
              <div className="grid w-full grid-cols-[minmax(0,1fr)_128px] gap-2 lg:w-auto lg:grid-cols-[256px_144px]">
                <div className="relative min-w-0">
                  <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sumi-faint" />
                  <input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder={text.search} className="h-10 w-full rounded-md border border-paper-border bg-paper-base pl-9 pr-3 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none" />
                </div>
                <div className="relative min-w-0">
                  <select value={sortOrder} onChange={(event) => setSortOrder(event.target.value as SortOrder)} className="h-10 w-full appearance-none rounded-md border border-paper-border bg-paper-base py-2 pl-3 pr-9 text-sm text-sumi-dim focus:border-vermilion focus:outline-none">
                    <option value="newest">{text.newest}</option>
                    <option value="oldest">{text.oldest}</option>
                  </select>
                  <ArrowUpDown size={13} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sumi-faint" />
                </div>
              </div>
            </div>

            <div className="mt-6 grid grid-cols-2 border-y border-paper-border sm:grid-cols-4">
              {[
                { label: text.allWorks, value: works.length, icon: <Layers size={15} className="text-vermilion" /> },
                { label: text.publishedWorks, value: dashboardStats.publishedWorks, icon: <Globe size={15} className="text-success" /> },
                { label: text.totalChapters, value: dashboardStats.totalChapters, icon: <FileText size={15} className="text-kinpaku" /> },
                { label: text.onlineChapters, value: dashboardStats.publishedChapters, icon: <CheckCircle2 size={15} className="text-aizuri" /> },
              ].map((stat, index) => (
                <div key={stat.label} className={`flex items-center gap-3 py-3 ${index % 2 === 1 ? 'border-l border-paper-border' : ''} ${index > 1 ? 'border-t border-paper-border sm:border-t-0' : ''} sm:border-l sm:first:border-l-0 sm:px-5 sm:first:pl-0`}>
                  <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-paper-surface">{stat.icon}</span>
                  <div className="min-w-0"><div className="text-xl font-semibold text-sumi">{stat.value}</div><div className="truncate text-[11px] text-sumi-faint">{stat.label}</div></div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div ref={pageScrollRef} className="flex-1 overflow-y-auto">
          <main className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
            <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex w-fit max-w-full gap-1 overflow-x-auto rounded-md bg-paper-surface p-1">
                {statusFilters.map((filter) => {
                  const count = filter.key === 'all' ? works.length : filter.key === 'published' ? dashboardStats.publishedWorks : dashboardStats.pendingWorks;
                  return (
                    <button key={filter.key} type="button" onClick={() => setStatusFilter(filter.key)} className={`flex h-8 shrink-0 items-center gap-1.5 rounded px-3 text-sm font-medium transition-colors ${statusFilter === filter.key ? 'bg-paper-raised text-sumi shadow-sm' : 'text-sumi-dim hover:text-sumi'}`}>
                      {filter.label}<span className="text-[11px] text-sumi-faint">{count}</span>
                    </button>
                  );
                })}
              </div>
              <span className="text-xs text-sumi-faint">{text.pendingWorks} {dashboardStats.pendingWorks} {text.worksUnit}</span>
            </div>

          {filteredWorks.length === 0 ? (
              <div className="flex min-h-64 items-center justify-center border-y border-paper-border">
                <div className="text-center text-sumi-faint">
                  <ImageIcon size={42} className="mx-auto" strokeWidth={1.25} />
                  <p className="mt-3 text-sm">{searchQuery ? text.noMatch : text.noWorks}</p>
                  {(searchQuery || statusFilter !== 'all') && <button type="button" onClick={() => { setSearchQuery(''); setStatusFilter('all'); }} className="mt-3 text-xs font-medium text-vermilion hover:text-vermilion-hover">清除筛选</button>}
                </div>
              </div>
          ) : (
              <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
              {filteredWorks.map((work) => {
                const cover = coverUrl(work);
                const stats = getWorkStats(work);
                return (
                    <button key={work.id} type="button" onClick={() => openDetail(work)} className="group grid min-w-0 grid-cols-[82px_1fr] gap-3 rounded-md border border-paper-border bg-paper-raised p-3 text-left transition-all hover:border-vermilion/30 hover:shadow-[0_10px_26px_rgba(24,27,25,0.07)] sm:grid-cols-[96px_1fr] sm:gap-4">
                    <div className="aspect-[3/4] overflow-hidden rounded-md border border-paper-border bg-paper-surface">
                        {cover ? <img src={cover} alt={work.title} className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.025]" loading="lazy" /> : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={30} className="text-sumi-faint" strokeWidth={1.25} /></div>}
                    </div>
                      <div className="flex min-w-0 flex-col py-0.5">
                        <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                            <h3 className="truncate text-base font-semibold text-sumi transition-colors group-hover:text-vermilion">{work.title}</h3>
                            <p className="mt-1 line-clamp-2 text-xs leading-[18px] text-sumi-dim sm:text-sm sm:leading-5">{work.description || text.noDescription}</p>
                        </div>
                          <span className={`shrink-0 rounded-sm px-2 py-1 text-[11px] font-medium ${work.is_published ? 'bg-success/10 text-success' : 'bg-paper-surface text-sumi-dim'}`}>{work.is_published ? text.published : text.draft}</span>
                      </div>
                        <div className="mt-auto pt-3">
                          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-sumi-faint">
                            <span className="flex items-center gap-1"><Layers size={12} />{stats.totalChapters} {text.chapterUnit}</span>
                            <span className="flex items-center gap-1"><Globe size={12} />{stats.publishedChapters} {text.online}</span>
                            <span className="hidden items-center gap-1 sm:flex"><CalendarDays size={12} />{formatDate(work.created_at)}</span>
                        </div>
                          <div className="mt-2.5 flex items-center gap-2">
                            <div className="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-paper-surface"><div className="h-full rounded-full bg-vermilion" style={{ width: `${stats.progress}%` }} /></div>
                            <span className="w-8 text-right text-[11px] text-sumi-faint">{stats.progress}%</span>
                          </div>
                          <div className="mt-2 flex items-center justify-end gap-1 text-xs font-medium text-vermilion opacity-80 transition-opacity group-hover:opacity-100">{text.manage}<ArrowRight size={13} /></div>
                        </div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
          </main>
        </div>
      </div>
    );
  }

  if (view === 'detail' && selectedWork) {
    const cover = coverUrl(selectedWork);
    const publishedCount = editChapters.filter((chapter) => chapter.is_published).length;
    const progress = editChapters.length > 0 ? Math.round((publishedCount / editChapters.length) * 100) : 0;
    const draftCount = editChapters.length - publishedCount;

    return (
      <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
        <header className="glass z-10 shrink-0 border-b border-paper-border">
          <div className="mx-auto flex min-h-16 w-full max-w-7xl items-center justify-between gap-3 px-3 md:px-8">
            <button type="button" onClick={backToList} className="flex h-9 min-w-0 items-center gap-1 rounded-md px-2 text-sm text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi">
              <ChevronLeft size={18} className="shrink-0" />
              <span className="hidden sm:inline">{text.backToList}</span>
              <span className="sm:hidden">{text.back}</span>
            </button>
            <div className="hidden min-w-0 items-center gap-2 text-xs text-sumi-faint md:flex">
              <span className="max-w-48 truncate text-sumi-dim">{selectedWork.title}</span>
              <span>/</span>
              <span>发布设置</span>
            </div>
            <button type="button" onClick={handleSave} disabled={saving} className="flex h-10 shrink-0 items-center gap-2 rounded-md bg-vermilion px-4 text-sm font-semibold text-white shadow-[0_5px_14px_rgba(211,58,44,0.18)] transition-all hover:bg-vermilion-hover hover:shadow-[0_7px_18px_rgba(211,58,44,0.24)] disabled:opacity-40">
              {saving ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
              <span className="hidden sm:inline">{text.savePublish}</span>
              <span className="sm:hidden">保存设置</span>
            </button>
          </div>
        </header>

        <div ref={pageScrollRef} className="flex-1 overflow-y-auto">
          <section className="border-b border-paper-border bg-paper-raised">
            <div className="mx-auto grid w-full max-w-7xl grid-cols-[92px_minmax(0,1fr)] gap-4 px-4 py-6 sm:grid-cols-[116px_minmax(0,1fr)] sm:gap-6 md:px-8 md:py-8 lg:grid-cols-[128px_minmax(0,1fr)_280px] lg:gap-8">
              <div className="relative aspect-[3/4] w-full overflow-hidden rounded-md border border-paper-border bg-paper-surface shadow-[0_14px_32px_rgba(24,27,25,0.12)]">
                {cover ? <img src={cover} alt={selectedWork.title} className="h-full w-full object-cover" /> : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={34} className="text-sumi-faint" strokeWidth={1.25} /></div>}
                <span className={`absolute left-2 top-2 h-2 w-2 rounded-full ring-4 ring-white/80 ${selectedWork.is_published ? 'bg-success' : 'bg-sumi-faint'}`} aria-hidden="true" />
              </div>

              <div className="min-w-0 self-center">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-xs">
                  <span className={`inline-flex items-center gap-1.5 rounded-sm px-2 py-1 font-medium ${selectedWork.is_published ? 'bg-success/10 text-success' : 'bg-paper-surface text-sumi-dim'}`}>
                    <span className={`h-1.5 w-1.5 rounded-full ${selectedWork.is_published ? 'bg-success' : 'bg-sumi-faint'}`} />
                    {selectedWork.is_published ? text.publicWork : text.privateWork}
                  </span>
                  <span className="flex items-center gap-1.5 text-sumi-faint"><CalendarDays size={13} />{text.createdAt} {formatDate(selectedWork.created_at)}</span>
                </div>
                <h1 className="font-display mt-3 break-words text-2xl font-bold text-sumi md:text-3xl">{selectedWork.title}</h1>
                <p className="mt-2 max-w-2xl text-sm leading-6 text-sumi-dim">{selectedWork.description || text.noDescription}</p>
                <div className="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-sumi-faint lg:hidden">
                  <span><strong className="mr-1 text-base font-semibold text-sumi">{editChapters.length}</strong>{text.totalChapters}</span>
                  <span><strong className="mr-1 text-base font-semibold text-success">{publishedCount}</strong>{text.online}</span>
                  <span><strong className="mr-1 text-base font-semibold text-sumi-dim">{draftCount}</strong>{text.draft}</span>
                </div>
              </div>

              <aside className="col-span-2 border-t border-paper-border pt-5 lg:col-span-1 lg:border-l lg:border-t-0 lg:pl-8 lg:pt-0">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <span className="flex items-center gap-2 text-sm font-medium text-sumi"><Clock3 size={15} className="text-success" />{text.onlineRate}</span>
                    <p className="mt-1 text-xs text-sumi-faint">已完成 {publishedCount} / {editChapters.length} 话</p>
                  </div>
                  <strong className="text-3xl font-semibold text-sumi">{progress}<span className="ml-0.5 text-sm font-medium text-sumi-faint">%</span></strong>
                </div>
                <div className="mt-4 h-2 overflow-hidden rounded-full bg-paper-surface"><div className="h-full rounded-full bg-success transition-[width] duration-300" style={{ width: `${progress}%` }} /></div>
                <div className="mt-5 hidden grid-cols-3 divide-x divide-paper-border lg:grid">
                  <div><strong className="block text-lg font-semibold text-sumi">{editChapters.length}</strong><span className="text-[11px] text-sumi-faint">全部章节</span></div>
                  <div className="pl-4"><strong className="block text-lg font-semibold text-success">{publishedCount}</strong><span className="text-[11px] text-sumi-faint">已上线</span></div>
                  <div className="pl-4"><strong className="block text-lg font-semibold text-sumi-dim">{draftCount}</strong><span className="text-[11px] text-sumi-faint">草稿</span></div>
                </div>
              </aside>
            </div>
          </section>

          <main className="mx-auto w-full max-w-7xl px-4 py-6 md:px-8 md:py-8">
            <section className="overflow-hidden rounded-md border border-paper-border bg-paper-raised shadow-[0_1px_2px_rgba(24,27,25,0.03)]">
              <div className="flex flex-col gap-4 border-b border-paper-border p-4 md:p-5 lg:flex-row lg:items-center lg:justify-between">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-vermilion-light text-vermilion"><SquarePen size={16} /></span>
                    <div className="min-w-0">
                      <h2 className="text-base font-semibold text-sumi">{text.chapterQueue}</h2>
                      <p className="mt-0.5 text-xs text-sumi-faint sm:text-sm">{text.queueSubtitle}</p>
                    </div>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-2 sm:flex sm:items-center">
                  <button type="button" onClick={selectAll} className="flex h-9 items-center justify-center gap-1.5 rounded-md border border-success/25 bg-success/[0.06] px-3 text-xs font-medium text-success transition-colors hover:bg-success/10">
                    <Globe size={14} />{text.allOnline}
                  </button>
                  <button type="button" onClick={deselectAll} className="flex h-9 items-center justify-center gap-1.5 rounded-md border border-paper-border bg-paper-base px-3 text-xs font-medium text-sumi-dim transition-colors hover:border-sumi-faint/50 hover:text-sumi">
                    <FileText size={14} />{text.allDraft}
                  </button>
                </div>
              </div>

              {editChapters.length === 0 ? (
                <div className="flex min-h-56 items-center justify-center px-4">
                  <div className="text-center text-sumi-faint"><FileText size={34} className="mx-auto" strokeWidth={1.25} /><p className="mt-3 text-sm">{text.noChapters}</p></div>
                </div>
              ) : (
                <div>
                  <div className="hidden grid-cols-[56px_112px_minmax(180px,1fr)_150px_56px] items-center gap-4 border-b border-paper-border bg-paper-surface/70 px-5 py-3 text-[11px] font-semibold text-sumi-faint md:grid">
                    <span>排序</span><span>章节</span><span>展示标题</span><span>发布状态</span><span className="text-center">预览</span>
                  </div>
                  {editChapters.map((chapter, index) => (
                    <div key={chapter.id} className={`group grid grid-cols-[36px_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-3 border-b border-paper-border px-3 py-4 transition-colors last:border-b-0 hover:bg-paper-base/70 md:grid-cols-[56px_112px_minmax(180px,1fr)_150px_56px] md:gap-4 md:px-5 md:py-3.5 ${chapter.is_published ? 'bg-success/[0.025]' : 'bg-paper-raised'}`}>
                      <div className="flex w-8 flex-col overflow-hidden rounded-md border border-paper-border bg-paper-raised md:w-9">
                        <button type="button" onClick={() => moveChapter(index, -1)} disabled={index === 0} className="flex h-7 items-center justify-center border-b border-paper-border text-sumi-faint transition-colors hover:bg-paper-surface hover:text-sumi disabled:cursor-not-allowed disabled:opacity-25" title="上移章节" aria-label={`上移${chapterTitle(chapter.chapter_number)}`}><ArrowUp size={13} /></button>
                        <button type="button" onClick={() => moveChapter(index, 1)} disabled={index === editChapters.length - 1} className="flex h-7 items-center justify-center text-sumi-faint transition-colors hover:bg-paper-surface hover:text-sumi disabled:cursor-not-allowed disabled:opacity-25" title="下移章节" aria-label={`下移${chapterTitle(chapter.chapter_number)}`}><ArrowDown size={13} /></button>
                      </div>

                      <div className="min-w-0">
                        <span className="block truncate text-sm font-semibold text-sumi">{chapterTitle(chapter.chapter_number)}</span>
                        <span className="mt-1 block text-[10px] text-sumi-faint">序号 {String(index + 1).padStart(2, '0')}</span>
                      </div>

                      <button type="button" role="switch" aria-checked={chapter.is_published} aria-label={`${chapterTitle(chapter.chapter_number)}${chapter.is_published ? '转为草稿' : '设为上线'}`} onClick={() => toggleChapter(chapter.id)} className="flex min-w-[96px] items-center justify-end gap-2 text-xs md:col-start-4 md:row-start-1 md:justify-start">
                        <span className={`relative h-5 w-9 shrink-0 rounded-full shadow-[inset_0_0_0_1px_rgba(24,27,25,0.04)] transition-colors ${chapter.is_published ? 'bg-success' : 'bg-paper-border'}`}><span className={`absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow-[0_1px_2px_rgba(24,27,25,0.16)] transition-transform ${chapter.is_published ? 'translate-x-4' : 'translate-x-0'}`} /></span>
                        <span className={`font-medium ${chapter.is_published ? 'text-success' : 'text-sumi-faint'}`}>{chapter.is_published ? '已上线' : text.draft}</span>
                      </button>

                      <label className="col-span-3 min-w-0 md:col-span-1 md:col-start-3 md:row-start-1">
                        <span className="mb-1.5 block text-[11px] font-medium text-sumi-faint md:hidden">展示标题</span>
                        <input value={chapter.display_title || ''} onChange={(event) => updateDisplayTitle(chapter.id, event.target.value)} placeholder={chapterTitle(chapter.chapter_number)} className="h-10 w-full min-w-0 rounded-md border border-paper-border bg-paper-raised px-3 text-sm text-sumi shadow-[inset_0_1px_1px_rgba(24,27,25,0.02)] placeholder-sumi-faint transition-[border-color,box-shadow] focus:border-vermilion focus:outline-none focus:ring-2 focus:ring-vermilion/10 md:h-9" />
                      </label>

                      <button type="button" onClick={() => void openReader(chapter.id)} className="col-span-3 flex h-9 items-center justify-center gap-1.5 rounded-md border border-paper-border bg-paper-raised text-xs font-medium text-sumi-dim transition-colors hover:border-vermilion/35 hover:bg-vermilion-light/40 hover:text-vermilion md:col-span-1 md:col-start-5 md:row-start-1 md:h-9 md:w-9 md:justify-self-center md:border-transparent md:bg-transparent" title={`预览${chapterTitle(chapter.chapter_number)}`} aria-label={`预览${chapterTitle(chapter.chapter_number)}`}>
                        <Eye size={15} /><span className="md:hidden">预览章节</span>
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </main>
        </div>
      </div>
    );
  }

  if (view === 'reader' && selectedWork) {
    const chapters = selectedWork.chapters;
    const nextDisabled = currentReaderIdx >= chapters.length - 1;
    const prevDisabled = currentReaderIdx <= 0;

    return (
      <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
        <header className="glass z-10 flex min-h-16 shrink-0 items-center justify-between gap-2 border-b border-paper-border px-3 md:px-6">
          <button type="button" onClick={closeReader} className="flex h-9 shrink-0 items-center gap-1 rounded-md px-2 text-sm text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi"><ChevronLeft size={18} />{text.back}</button>
          <div className="flex min-w-0 items-center gap-2 px-1">
            <span className="hidden max-w-48 truncate text-sm font-semibold text-sumi sm:inline">{selectedWork.title}</span>
            <span className="hidden text-sumi-faint sm:inline">/</span>
            <span className="truncate text-sm text-sumi-dim">{readerChapterInfo?.display_title || (readerChapterInfo?.chapter_number ? chapterTitle(readerChapterInfo.chapter_number) : '')}</span>
          </div>
          <select value={readerChapterId ?? ''} aria-label="切换章节" onChange={(event) => { const id = Number(event.target.value); if (id) void openReader(id); }} className="h-9 max-w-[132px] shrink-0 rounded-md border border-paper-border bg-paper-raised px-2 text-xs text-sumi-dim focus:border-vermilion focus:outline-none sm:max-w-[180px]">
            {chapters.map((chapter) => <option key={chapter.id} value={chapter.id}>{chapterTitle(chapter.chapter_number)} {chapter.display_title || ''}</option>)}
          </select>
        </header>
        <div className="flex-1 overflow-y-auto bg-paper-surface/60">
          {readerLoading ? (
            <div className="flex h-full items-center justify-center"><Loader2 size={28} className="animate-spin text-vermilion" /></div>
          ) : readerImages.length === 0 ? (
            <div className="flex h-full items-center justify-center">
              <div className="px-4 text-center text-sumi-faint">
                <ImageIcon size={52} className="mx-auto mb-4" strokeWidth={1.1} />
                <p className="text-base">{text.noManga}</p>
                <button type="button" onClick={closeReader} className="mt-3 text-sm font-medium text-vermilion transition-colors hover:text-vermilion-hover">{text.backToManage}</button>
              </div>
            </div>
          ) : (
            <div className="mx-auto max-w-3xl bg-paper-raised shadow-[0_0_40px_rgba(24,27,25,0.08)]">
              {readerImages.map((image) => <img key={image.id} src={mangaImageUrl(image.image_path) || ''} alt={`${text.chapterPrefix} ${image.image_number} ${text.pageUnit}`} className="block w-full" loading="lazy" />)}
            </div>
          )}
        </div>
        <footer className="flex shrink-0 items-center justify-center gap-3 border-t border-paper-border bg-paper-raised px-3 py-3 md:gap-8">
          <button type="button" onClick={() => navigateReaderChapter(-1)} disabled={prevDisabled} className="flex h-9 items-center gap-1 rounded-md border border-paper-border px-3 text-sm font-medium text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi disabled:cursor-not-allowed disabled:opacity-30"><ChevronLeft size={16} /><span className="hidden sm:inline">{text.prevChapter}</span></button>
          <span className="min-w-14 text-center text-xs text-sumi-faint">{currentReaderIdx + 1} / {chapters.length}</span>
          <button type="button" onClick={() => navigateReaderChapter(1)} disabled={nextDisabled} className="flex h-9 items-center gap-1 rounded-md border border-paper-border px-3 text-sm font-medium text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi disabled:cursor-not-allowed disabled:opacity-30"><span className="hidden sm:inline">{text.nextChapter}</span><ChevronRight size={16} /></button>
        </footer>
      </div>
    );
  }

  return null;
}
