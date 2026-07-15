import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  BookOpenText,
  FileText,
  Globe,
  KeyRound,
  LogIn,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Paintbrush,
  Sparkles,
} from 'lucide-react';
import ApiSettingsPage from './components/ApiSettingsPage';
import HomePage from './components/HomePage';
import ImageGenPage from './components/ImageGenPage';
import LoginPage from './components/LoginPage';
import MangaAgentPage from './components/MangaAgentPage';
import MyWorksPage from './components/MyWorksPage';
import SquarePage from './components/SquarePage';
import ThemeToggle from './components/ThemeToggle';
import WorkspaceEditor from './components/WorkspaceEditor';
import { isAuthenticated, logoutUser, type Story } from './api';
import {
  isProtectedRoute,
  parseAppHash,
  pushAppRoute,
  replaceAppRoute,
  type AppRoute,
  type AppView,
} from './appRouting';

type View = AppView;
type TopLevelView = Exclude<AppView, 'editor'>;

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';

function useIsMobile() {
  const read = () =>
    navigator.maxTouchPoints > 0 ||
    window.matchMedia('(any-pointer:coarse)').matches ||
    window.matchMedia('(max-width:1024px)').matches ||
    window.matchMedia('(pointer:coarse)').matches;

  const [isMobile, setIsMobile] = useState(read);

  useEffect(() => {
    const width = window.matchMedia('(max-width:1024px)');
    const pointer = window.matchMedia('(pointer:coarse)');
    const anyPointer = window.matchMedia('(any-pointer:coarse)');
    let frame = 0;

    const sync = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => setIsMobile(read()));
    };

    width.addEventListener('change', sync);
    pointer.addEventListener('change', sync);
    anyPointer.addEventListener('change', sync);
    window.addEventListener('resize', sync);

    return () => {
      cancelAnimationFrame(frame);
      width.removeEventListener('change', sync);
      pointer.removeEventListener('change', sync);
      anyPointer.removeEventListener('change', sync);
      window.removeEventListener('resize', sync);
    };
  }, []);

  return isMobile;
}

function clearWorkspaceState() {
  localStorage.removeItem(LS_STORY_ID);
  localStorage.removeItem(LS_CHAPTER_ID);
  localStorage.removeItem(LS_CHAPTER_IDX);
}

export default function App() {
  const isMobile = useIsMobile();
  const [authenticated, setAuthenticated] = useState(false);
  const [authCheck, setAuthCheck] = useState(false);
  const [route, setRoute] = useState<AppRoute | null>(() => parseAppHash(window.location.hash));
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginMessage, setLoginMessage] = useState('请先登录后再使用该功能');
  const [pendingRoute, setPendingRoute] = useState<AppRoute | null>(null);
  const [pendingCreateStory, setPendingCreateStory] = useState(false);
  const [workspaceCreateSignal, setWorkspaceCreateSignal] = useState<number | null>(null);
  const [navigationMessage, setNavigationMessage] = useState('');

  const visibleRoute = route && (!authenticated && isProtectedRoute(route)) ? { view: 'square' as const } : route;
  const view: View = visibleRoute?.view ?? 'square';
  // A public work is a destination in its own right. Keep it outside the
  // creator-console chrome so a shared URL opens as an uninterrupted reading
  // page, rather than as a panel inside the application shell.
  const isSquareStoryRoute = visibleRoute?.view === 'square' && 'storyId' in visibleRoute;

  useEffect(() => {
    setAuthenticated(isAuthenticated());
    setAuthCheck(true);
  }, []);

  useEffect(() => {
    const syncRoute = () => {
      const parsed = parseAppHash(window.location.hash);
      if (parsed) {
        setRoute(parsed);
        return;
      }
      replaceAppRoute({ view: isAuthenticated() ? 'home' : 'square' });
    };

    window.addEventListener('hashchange', syncRoute);
    syncRoute();
    return () => window.removeEventListener('hashchange', syncRoute);
  }, []);

  useEffect(() => {
    if (!authCheck || authenticated || !route || !isProtectedRoute(route)) return;
    setPendingRoute(route);
    if (!loginOpen) {
      setLoginMessage('请先登录后再使用该功能');
      setLoginOpen(true);
    }
  }, [authCheck, authenticated, loginOpen, route]);

  useEffect(() => {
    const handleExpired = () => {
      setAuthenticated(false);
      setLoginMessage('登录已过期，请重新登录');
      setPendingRoute(route && isProtectedRoute(route) ? route : null);
      setLoginOpen(true);
      clearWorkspaceState();
    };
    window.addEventListener('artverse:auth-expired', handleExpired);
    return () => window.removeEventListener('artverse:auth-expired', handleExpired);
  }, [route]);

  const consumeWorkspaceCreateSignal = () => {
    setWorkspaceCreateSignal(null);
  };

  const requireLogin = (target?: AppRoute) => {
    setLoginMessage('请先登录后再使用该功能');
    setPendingRoute(target || null);
    setLoginOpen(true);
    if (target) pushAppRoute(target);
  };

  const loadEditor = (story: Story) => {
    const previousStoryId = Number(localStorage.getItem(LS_STORY_ID));
    if (previousStoryId !== story.id) {
      localStorage.removeItem(LS_CHAPTER_ID);
      localStorage.removeItem(LS_CHAPTER_IDX);
    }
    localStorage.setItem(LS_STORY_ID, String(story.id));
    setNavigationMessage('');
    pushAppRoute({ view: 'editor', storyId: story.id });
  };

  const leaveEditor = () => {
    clearWorkspaceState();
    setNavigationMessage('');
    pushAppRoute({ view: 'workspace' });
  };

  const openWorkspaceCreateStory = () => {
    if (!authenticated) {
      setPendingCreateStory(true);
      requireLogin({ view: 'workspace' });
      return;
    }
    setPendingCreateStory(false);
    clearWorkspaceState();
    setWorkspaceCreateSignal((prev) => (typeof prev === 'number' ? prev + 1 : 1));
    setNavigationMessage('');
    pushAppRoute({ view: 'workspace' });
  };

  const handleAuthSuccess = () => {
    setAuthenticated(true);
    setLoginOpen(false);
    if (pendingCreateStory) {
      clearWorkspaceState();
      setWorkspaceCreateSignal((prev) => (typeof prev === 'number' ? prev + 1 : 1));
      setPendingCreateStory(false);
    }
    if (pendingRoute) {
      replaceAppRoute(pendingRoute);
      setPendingRoute(null);
    }
  };

  const cancelLogin = () => {
    setLoginOpen(false);
    setPendingRoute(null);
    setPendingCreateStory(false);
    clearWorkspaceState();
    replaceAppRoute({ view: 'square' });
  };

  const handleLogout = async () => {
    await logoutUser();
    setAuthenticated(false);
    setPendingRoute(null);
    setPendingCreateStory(false);
    setLoginOpen(false);
    clearWorkspaceState();
    replaceAppRoute({ view: 'square' });
  };

  const goView = (target: TopLevelView) => {
    const targetRoute: AppRoute = { view: target };
    if (target !== 'square' && !authenticated) {
      requireLogin(targetRoute);
      return;
    }
    if (view === 'editor') {
      clearWorkspaceState();
    }
    setNavigationMessage('');
    pushAppRoute(targetRoute);
  };

  const handleEditorLoadError = useCallback((message: string) => {
    if (!isAuthenticated()) return;
    clearWorkspaceState();
    setNavigationMessage(message || '故事不存在或当前账号无权访问，已返回故事工作区。');
    replaceAppRoute({ view: 'workspace' });
  }, []);

  const navItem = (icon: ReactNode, label: string, target: TopLevelView) => {
    const active = view === target;
    return (
      <button
        type="button"
        onClick={() => goView(target)}
        title={!sidebarOpen ? label : undefined}
        className={
          'group relative flex min-h-10 w-full items-center rounded-xl px-3 text-left text-sm font-medium transition-all duration-200 '
          + (active
            ? 'bg-accent-muted text-accent font-semibold shadow-[inset_0_0_0_1px_rgba(255,255,255,0.06)]'
            : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary')
        }
      >
        <span className="flex items-center gap-3 whitespace-nowrap">
          <span className={active ? 'scale-110 transition-transform duration-300' : 'transition-transform duration-300'}>
            {icon}
          </span>
          {sidebarOpen && <span>{label}</span>}
        </span>
      </button>
    );
  };

  const mobileNavItem = (icon: ReactNode, label: string, target: TopLevelView) => {
    const active = view === target;
    return (
      <button
        type="button"
        onClick={() => goView(target)}
        className={
          'relative flex min-w-0 flex-1 flex-col items-center justify-center gap-1 px-1 py-2 text-[10px] font-medium transition-all duration-200 '
          + (active ? 'text-accent' : 'text-text-muted hover:text-text-secondary')
        }
      >
        {active && <span className="absolute -top-0.5 left-1/2 -translate-x-1/2 h-1 w-1 rounded-full bg-accent" />}
        <span className={active ? 'scale-110 transition-transform duration-300' : 'transition-transform duration-300'}>
          {icon}
        </span>
        <span className="max-w-full truncate">{label}</span>
      </button>
    );
  };

  if (!authCheck) {
    return (
      <div className="flex h-dvh w-screen items-center justify-center bg-bg-base">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-accent" />
      </div>
    );
  }

  return (
    <div className="relative flex h-dvh w-screen overflow-hidden bg-bg-base text-text-primary">
      {!isMobile && !isSquareStoryRoute && (
        <aside
          className={
            'flex shrink-0 flex-col border-r border-border glass-panel transition-[width] duration-300 '
            + (sidebarOpen ? 'w-[232px]' : 'w-[68px]')
          }
        >
          <div className="flex h-16 items-center gap-3 border-b border-border px-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-accent text-white shadow-glow">
              <Sparkles size={18} />
            </div>
            {sidebarOpen && (
              <div className="min-w-0 flex-1">
                <div className="font-display text-base font-bold text-text-primary">ArtVerse</div>
                <div className="text-[10px] text-text-muted">AI 漫画创作工坊</div>
              </div>
            )}
            <button
              type="button"
              onClick={() => setSidebarOpen((prev) => !prev)}
              className="ml-auto flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-accent-soft hover:text-text-primary"
              aria-label={sidebarOpen ? '收起侧栏' : '展开侧栏'}
              title={sidebarOpen ? '收起侧栏' : '展开侧栏'}
            >
              {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
            </button>
          </div>

          <nav className="flex flex-1 flex-col gap-1 px-2 py-4">
            {sidebarOpen && <p className="mb-1 px-3 text-[10px] font-semibold uppercase tracking-wider text-text-muted">创作空间</p>}
            {navItem(<Sparkles size={18} />, '创作助手', 'home')}
            {navItem(<BookOpenText size={18} />, '故事工作区', 'workspace')}
            {navItem(<Paintbrush size={18} />, 'AI 生图', 'imagegen')}
            {sidebarOpen && <p className="mb-1 mt-4 px-3 text-[10px] font-semibold uppercase tracking-wider text-text-muted">发现与管理</p>}
            {navItem(<Globe size={18} />, '作品广场', 'square')}
            {navItem(<FileText size={18} />, '作品管理', 'myworks')}
          </nav>

          <div className="flex flex-col gap-1 border-t border-border px-2 py-3">
            {authenticated ? (
              <>
                {navItem(<KeyRound size={18} />, 'API 设置', 'settings')}
                <button
                  type="button"
                  onClick={() => { void handleLogout(); }}
                  title={!sidebarOpen ? '退出登录' : undefined}
                  className="flex min-h-10 w-full items-center gap-3 rounded-xl px-3 text-sm font-medium text-text-secondary transition-colors hover:bg-accent-soft hover:text-accent"
                >
                  <LogOut size={18} />
                  {sidebarOpen && <span>退出登录</span>}
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => requireLogin()}
                title={!sidebarOpen ? '登录' : undefined}
                className="flex min-h-10 w-full items-center gap-3 rounded-xl px-3 text-sm font-medium text-text-secondary transition-colors hover:bg-accent-soft hover:text-text-primary"
              >
                <LogIn size={18} />
                {sidebarOpen && <span>登录</span>}
              </button>
            )}
            <ThemeToggle compact={!sidebarOpen} />
          </div>
        </aside>
      )}

      <div className={'flex min-h-0 min-w-0 flex-1 flex-col ' + (isMobile && view !== 'editor' && !isSquareStoryRoute ? 'pb-[calc(64px+env(safe-area-inset-bottom))]' : '')}>
        {isMobile && view !== 'editor' && !isSquareStoryRoute && (
          <header className="flex h-14 shrink-0 items-center justify-between border-b border-border glass-panel px-4">
            <button type="button" onClick={() => goView('home')} className="flex items-center gap-2" aria-label="返回创作助手">
              <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent text-white shadow-glow"><Sparkles size={16} /></span>
              <span className="font-display text-base font-bold text-text-primary">ArtVerse</span>
            </button>
            <div className="flex items-center gap-2">
              <ThemeToggle compact />
              <button
                type="button"
                onClick={() => authenticated ? goView('settings') : requireLogin()}
                className="flex h-9 w-9 items-center justify-center rounded-lg border border-border bg-bg-surface text-text-secondary"
                aria-label={authenticated ? 'API 设置' : '登录'}
              >
                {authenticated ? <KeyRound size={17} /> : <LogIn size={17} />}
              </button>
            </div>
          </header>
        )}
        {navigationMessage && (
          <div className="shrink-0 border-b border-warning/30 bg-warning/10 px-4 py-2 text-sm text-text-primary" role="status">
            {navigationMessage}
          </div>
        )}
        {view === 'home' && <MangaAgentPage onCreateStory={openWorkspaceCreateStory} />}
        {view === 'square' && <SquarePage />}
        {view === 'workspace' && (
          <HomePage
            onSelectStory={loadEditor}
            createStorySignal={workspaceCreateSignal}
            onCreateStorySignalConsumed={consumeWorkspaceCreateSignal}
          />
        )}
        {visibleRoute?.view === 'editor' && (
          <WorkspaceEditor storyId={visibleRoute.storyId} onBack={leaveEditor} onLoadError={handleEditorLoadError} />
        )}
        {view === 'imagegen' && <ImageGenPage />}
        {view === 'myworks' && <MyWorksPage />}
        {view === 'settings' && <ApiSettingsPage />}
      </div>

      {isMobile && view !== 'editor' && !isSquareStoryRoute && (
        <nav className="fixed inset-x-0 bottom-0 z-40 flex h-[calc(64px+env(safe-area-inset-bottom))] border-t border-border glass-panel pb-[env(safe-area-inset-bottom)]" aria-label="主导航">
          {mobileNavItem(<Sparkles size={19} />, '创作', 'home')}
          {mobileNavItem(<BookOpenText size={19} />, '故事', 'workspace')}
          {mobileNavItem(<Paintbrush size={19} />, '生图', 'imagegen')}
          {mobileNavItem(<Globe size={19} />, '广场', 'square')}
          {mobileNavItem(<FileText size={19} />, '作品', 'myworks')}
        </nav>
      )}

      {loginOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-bg-overlay p-4 backdrop-blur-sm"
          onClick={cancelLogin}
        >
          <div className="w-full max-w-sm animate-fade-in" onClick={(event) => event.stopPropagation()}>
            <LoginPage
              variant="modal"
              message={loginMessage}
              onCancel={cancelLogin}
              onAuthSuccess={handleAuthSuccess}
            />
          </div>
        </div>
      )}
    </div>
  );
}
