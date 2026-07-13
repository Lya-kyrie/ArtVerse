import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  BookOpenText,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Compass,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  Search,
  X,
} from 'lucide-react';
import {
  getSquareStoryDetail,
  listSquareStories,
  mangaImageUrl,
  refImageUrl,
  type SquareStory,
  type SquareStoryDetail,
} from '../api';

const PAGE_SIZE = 12;

const formatDate = (date: string) => {
  if (!date) return '近期发布';
  const value = new Date(date);
  return Number.isNaN(value.getTime())
    ? '近期发布'
    : value.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' });
};

const formatStyle = (style: string) => style?.trim().replaceAll('_', ' ') || '漫画故事';

export default function SquarePage() {
  const [stories, setStories] = useState<SquareStory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [selected, setSelected] = useState<SquareStoryDetail | null>(null);
  const [selectedChapterId, setSelectedChapterId] = useState<number | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const detailScrollRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async (nextPage: number, keyword: string) => {
    setLoading(true);
    setError('');
    try {
      const result = await listSquareStories(nextPage, PAGE_SIZE, keyword || undefined);
      setStories(result.content);
      setTotalPages(result.total_pages);
      setTotalElements(result.total_elements);
      setPage(nextPage);
    } catch (loadError) {
      console.error(loadError);
      setError('作品加载失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(0, search);
  }, [load, search]);

  const handleSearch = () => setSearch(searchInput.trim());

  const clearSearch = () => {
    setSearchInput('');
    setSearch('');
  };

  const openDetail = async (id: number) => {
    setDetailLoading(true);
    setDetailError('');
    try {
      const detail = await getSquareStoryDetail(id);
      setSelected(detail);
      setSelectedChapterId(detail.chapters.find((chapter) => chapter.images.length > 0)?.id ?? detail.chapters[0]?.id ?? null);
      requestAnimationFrame(() => detailScrollRef.current?.scrollTo({ top: 0 }));
    } catch (loadError) {
      console.error(loadError);
      setDetailError('作品详情加载失败，请稍后重试');
    } finally {
      setDetailLoading(false);
    }
  };

  const selectedChapterIndex = selected?.chapters.findIndex((chapter) => chapter.id === selectedChapterId) ?? -1;
  const selectedChapter = selectedChapterIndex >= 0 ? selected?.chapters[selectedChapterIndex] : undefined;

  const selectChapter = (chapterId: number) => {
    setSelectedChapterId(chapterId);
    detailScrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const navigateChapter = (direction: -1 | 1) => {
    if (!selected) return;
    const nextChapter = selected.chapters[selectedChapterIndex + direction];
    if (nextChapter) selectChapter(nextChapter.id);
  };

  if (detailLoading) {
    return (
      <div className="flex flex-1 items-center justify-center bg-paper-base">
        <div className="flex items-center gap-3 text-sm text-sumi-dim">
          <Loader2 size={20} className="animate-spin text-vermilion" />正在打开作品
        </div>
      </div>
    );
  }

  if (selected) {
    const chapterTitle = selectedChapter
      ? selectedChapter.display_title || `第 ${selectedChapter.chapter_number} 话`
      : '';
    const sortedImages = selectedChapter
      ? [...selectedChapter.images].sort((a, b) => a.image_number - b.image_number)
      : [];

    return (
      <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
        <header className="glass z-20 flex min-h-16 shrink-0 items-center justify-between gap-2 border-b border-paper-border px-3 md:px-6">
          <button
            type="button"
            onClick={() => { setSelected(null); setSelectedChapterId(null); }}
            className="flex h-9 shrink-0 items-center gap-1 rounded-md px-2 text-sm font-medium text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi"
          >
            <ChevronLeft size={18} />返回广场
          </button>
          <div className="flex min-w-0 items-center gap-2 px-1">
            <span className="hidden max-w-48 truncate text-sm font-semibold text-sumi sm:inline">{selected.title}</span>
            {chapterTitle && <span className="hidden text-sumi-faint sm:inline">/</span>}
            <span className="truncate text-sm text-sumi-dim">{chapterTitle}</span>
          </div>
          {selected.chapters.length > 0 ? (
            <select
              value={selectedChapterId ?? ''}
              aria-label="切换章节"
              onChange={(event) => selectChapter(Number(event.target.value))}
              className="h-9 max-w-[132px] shrink-0 rounded-md border border-paper-border bg-paper-raised px-2 text-xs text-sumi-dim focus:border-vermilion focus:outline-none sm:max-w-[200px]"
            >
              {selected.chapters.map((chapter) => (
                <option key={chapter.id} value={chapter.id}>
                  第 {chapter.chapter_number} 话 {chapter.display_title || ''}
                </option>
              ))}
            </select>
          ) : <span className="w-9 shrink-0" />}
        </header>

        <div ref={detailScrollRef} className="flex-1 overflow-y-auto bg-paper-surface/60">
          {selected.chapters.length === 0 ? (
            <div className="flex h-full items-center justify-center px-4 text-center text-sumi-faint">
              <div><BookOpenText size={52} className="mx-auto mb-4" strokeWidth={1.1} /><p className="text-base">作者还没有发布章节</p></div>
            </div>
          ) : sortedImages.length === 0 ? (
            <div className="flex h-full items-center justify-center px-4 text-center text-sumi-faint">
              <div><ImageIcon size={52} className="mx-auto mb-4" strokeWidth={1.1} /><p className="text-base">该话暂无漫画画面</p></div>
            </div>
          ) : (
            <main className="mx-auto max-w-3xl bg-paper-raised shadow-[0_0_40px_rgba(24,27,25,0.08)]" aria-label={`${selected.title} ${chapterTitle}`}>
              {sortedImages.map((image) => (
                <img
                  key={image.id}
                  src={mangaImageUrl(image.image_url)}
                  alt={`${chapterTitle} 第 ${image.image_number} 页`}
                  className="block h-auto w-full"
                  loading="lazy"
                />
              ))}
            </main>
          )}
        </div>

        {selected.chapters.length > 0 && (
          <footer className="flex shrink-0 items-center justify-center gap-3 border-t border-paper-border bg-paper-raised px-3 py-3 md:gap-8">
            <button type="button" onClick={() => navigateChapter(-1)} disabled={selectedChapterIndex <= 0} className="flex h-9 items-center gap-1 rounded-md border border-paper-border px-3 text-sm font-medium text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi disabled:cursor-not-allowed disabled:opacity-30"><ChevronLeft size={16} /><span className="hidden sm:inline">上一话</span></button>
            <span className="min-w-24 text-center text-xs text-sumi-faint">{selectedChapterIndex + 1} / {selected.chapters.length}<span className="ml-2 hidden sm:inline">· {sortedImages.length} 页</span></span>
            <button type="button" onClick={() => navigateChapter(1)} disabled={selectedChapterIndex >= selected.chapters.length - 1} className="flex h-9 items-center gap-1 rounded-md border border-paper-border px-3 text-sm font-medium text-sumi-dim transition-colors hover:bg-paper-surface hover:text-sumi disabled:cursor-not-allowed disabled:opacity-30"><span className="hidden sm:inline">下一话</span><ChevronRight size={16} /></button>
          </footer>
        )}
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
      <header className="shrink-0 border-b border-paper-border bg-paper-raised">
        <div className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <div className="flex items-center gap-2 text-xs font-semibold text-vermilion"><Compass size={15} />作品广场</div>
              <h1 className="font-display mt-1.5 text-2xl font-bold text-sumi md:text-3xl">发现值得追读的漫画故事</h1>
              <p className="mt-1.5 text-sm text-sumi-dim">浏览创作者公开作品，从封面进入完整章节。</p>
            </div>
            <div className="flex w-full gap-2 lg:w-[420px]">
              <div className="relative min-w-0 flex-1">
                <Search size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sumi-faint" />
                <input
                  value={searchInput}
                  onChange={(event) => setSearchInput(event.target.value)}
                  onKeyDown={(event) => { if (event.key === 'Enter') handleSearch(); }}
                  placeholder="搜索作品名称"
                  className="h-10 w-full rounded-md border border-paper-border bg-paper-base pl-9 pr-9 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none"
                />
                {searchInput && (
                  <button type="button" onClick={clearSearch} className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-md text-sumi-faint hover:bg-paper-surface hover:text-sumi" aria-label="清除搜索"><X size={14} /></button>
                )}
              </div>
              <button type="button" onClick={handleSearch} className="flex h-10 shrink-0 items-center gap-1.5 rounded-md bg-vermilion px-4 text-sm font-semibold text-white transition-colors hover:bg-vermilion-hover">搜索<ArrowRight size={15} /></button>
            </div>
          </div>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto">
        <main className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="mb-5 flex items-center justify-between gap-4">
            <div className="flex items-baseline gap-2">
              <h2 className="text-base font-semibold text-sumi">{search ? `“${search}” 的搜索结果` : '全部公开作品'}</h2>
              {!loading && !error && <span className="text-xs text-sumi-faint">{totalElements} 部</span>}
            </div>
            {search && <button type="button" onClick={clearSearch} className="text-xs font-medium text-vermilion hover:text-vermilion-hover">查看全部</button>}
          </div>

          {loading ? (
            <div className="grid grid-cols-2 gap-x-3 gap-y-6 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
              {Array.from({ length: PAGE_SIZE }, (_, index) => (
                <div key={index} className="animate-pulse">
                  <div className="aspect-[3/4] rounded-md bg-paper-surface" />
                  <div className="mt-3 h-4 w-3/4 rounded bg-paper-surface" />
                  <div className="mt-2 h-3 w-1/2 rounded bg-paper-surface" />
                </div>
              ))}
            </div>
          ) : error || detailError ? (
            <div className="flex min-h-72 flex-col items-center justify-center border-y border-paper-border text-center">
              <RefreshCw size={34} className="text-sumi-faint" strokeWidth={1.4} />
              <p className="mt-3 text-sm font-medium text-sumi">{error || detailError}</p>
              <button type="button" onClick={() => { setDetailError(''); void load(page, search); }} className="mt-4 flex items-center gap-1.5 rounded-md border border-paper-border bg-paper-raised px-3 py-2 text-sm text-sumi-dim hover:border-vermilion/40 hover:text-vermilion"><RefreshCw size={14} />重新加载</button>
            </div>
          ) : stories.length === 0 ? (
            <div className="flex min-h-72 flex-col items-center justify-center border-y border-paper-border text-center">
              <BookOpenText size={42} className="text-sumi-faint" strokeWidth={1.25} />
              <p className="mt-3 text-sm font-medium text-sumi">{search ? '没有找到匹配的作品' : '暂时还没有公开作品'}</p>
              <p className="mt-1 text-xs text-sumi-faint">{search ? '换一个更简短的关键词试试' : '创作者发布后会展示在这里'}</p>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-x-3 gap-y-6 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
              {stories.map((story) => (
                <button key={story.id} type="button" onClick={() => void openDetail(story.id)} className="group min-w-0 text-left">
                  <div className="relative aspect-[3/4] overflow-hidden rounded-md border border-paper-border bg-paper-surface shadow-[0_1px_2px_rgba(24,27,25,0.04)] transition-all duration-300 group-hover:-translate-y-1 group-hover:border-vermilion/30 group-hover:shadow-[0_14px_30px_rgba(24,27,25,0.12)]">
                    {story.cover_url ? (
                      <img src={refImageUrl(story.cover_url)} alt={story.title} className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-[1.035]" loading="lazy" />
                    ) : (
                      <div className="flex h-full flex-col items-center justify-center gap-2 text-sumi-faint"><BookOpenText size={32} strokeWidth={1.25} /><span className="text-[11px]">暂无封面</span></div>
                    )}
                    <span className="absolute left-2 top-2 max-w-[calc(100%-1rem)] truncate rounded-sm bg-paper-raised/92 px-2 py-1 text-[10px] font-medium text-sumi-dim shadow-sm backdrop-blur-sm">{formatStyle(story.manga_style)}</span>
                    <span className="absolute bottom-2 right-2 flex h-8 w-8 items-center justify-center rounded-md bg-sumi/78 text-white opacity-0 transition-all duration-200 group-hover:opacity-100"><ChevronRight size={17} /></span>
                  </div>
                  <h3 className="mt-3 truncate text-sm font-semibold text-sumi transition-colors group-hover:text-vermilion">{story.title}</h3>
                  <p className="mt-1 line-clamp-2 min-h-9 text-xs leading-[18px] text-sumi-dim">{story.description || '作者暂未填写作品简介。'}</p>
                  <div className="mt-2 flex items-center gap-1 text-[11px] text-sumi-faint"><CalendarDays size={12} />{formatDate(story.published_at)}</div>
                </button>
              ))}
            </div>
          )}

          {!loading && !error && totalPages > 1 && (
            <nav className="mt-9 flex items-center justify-center gap-3 border-t border-paper-border pt-5" aria-label="作品分页">
              <button type="button" onClick={() => void load(page - 1, search)} disabled={page === 0} className="flex h-9 items-center gap-1 rounded-md border border-paper-border bg-paper-raised px-3 text-sm text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion disabled:cursor-not-allowed disabled:opacity-35"><ArrowLeft size={15} />上一页</button>
              <span className="min-w-20 text-center text-xs text-sumi-faint">{page + 1} / {totalPages}</span>
              <button type="button" onClick={() => void load(page + 1, search)} disabled={page >= totalPages - 1} className="flex h-9 items-center gap-1 rounded-md border border-paper-border bg-paper-raised px-3 text-sm text-sumi-dim transition-colors hover:border-vermilion/40 hover:text-vermilion disabled:cursor-not-allowed disabled:opacity-35">下一页<ArrowRight size={15} /></button>
            </nav>
          )}
        </main>
      </div>
    </div>
  );
}
