import { useEffect, useState, type ReactNode } from 'react';
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
import WorkspaceEditor from './components/WorkspaceEditor';
import { isAuthenticated, logoutUser, type Story } from './api';

type View = 'home' | 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks' | 'settings';

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
  const [view, setView] = useState<View>('home');
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginMessage, setLoginMessage] = useState('请先登录后再使用该功能');
  const [pendingView, setPendingView] = useState<View | null>(null);
  const [pendingCreateStory, setPendingCreateStory] = useState(false);
  const [workspaceCreateSignal, setWorkspaceCreateSignal] = useState<number | null>(null);
  const [activeStoryId, setActiveStoryId] = useState<number | null>(null);

  useEffect(() => {
    setAuthenticated(isAuthenticated());
    setAuthCheck(true);
  }, []);

  useEffect(() => {
    const handleExpired = () => {
      setAuthenticated(false);
      setLoginMessage('登录已过期，请重新登录');
      setPendingView(view === 'square' ? null : view);
      setLoginOpen(true);
      clearWorkspaceState();
      if (view !== 'square') setView('square');
    };
    window.addEventListener('artverse:auth-expired', handleExpired);
    return () => window.removeEventListener('artverse:auth-expired', handleExpired);
  }, [view]);

  const consumeWorkspaceCreateSignal = () => {
    setWorkspaceCreateSignal(null);
  };

  const requireLogin = (target?: View) => {
    setLoginMessage('请先登录后再使用该功能');
    setPendingView(target || null);
    setLoginOpen(true);
  };

  const loadEditor = (story: Story) => {
    setActiveStoryId(story.id);
    localStorage.setItem(LS_STORY_ID, String(story.id));
    setView('editor');
  };

  const leaveEditor = () => {
    setActiveStoryId(null);
    clearWorkspaceState();
    setView('workspace');
  };

  const openWorkspaceCreateStory = () => {
    if (!authenticated) {
      setPendingCreateStory(true);
      requireLogin('workspace');
      return;
    }
    setPendingCreateStory(false);
    setActiveStoryId(null);
    clearWorkspaceState();
    setWorkspaceCreateSignal((prev) => (typeof prev === 'number' ? prev + 1 : 1));
    setView('workspace');
  };

  const handleAuthSuccess = () => {
    setAuthenticated(true);
    setLoginOpen(false);
    clearWorkspaceState();
    if (pendingCreateStory) {
      setWorkspaceCreateSignal((prev) => (typeof prev === 'number' ? prev + 1 : 1));
      setPendingCreateStory(false);
    }
    if (pendingView) {
      setView(pendingView);
      setPendingView(null);
    }
  };

  const handleLogout = async () => {
    await logoutUser();
    setAuthenticated(false);
    setPendingView(null);
    setPendingCreateStory(false);
    setLoginOpen(false);
    setActiveStoryId(null);
    setView('home');
    clearWorkspaceState();
  };

  const goView = (target: View) => {
    if (target !== 'square' && !authenticated) {
      requireLogin(target);
      return;
    }
    if (view === 'editor' && target !== 'editor') {
      setActiveStoryId(null);
      clearWorkspaceState();
    }
    setView(target);
  };

  const navItem = (icon: ReactNode, label: string, target: View) => (
    <button
      type="button"
      onClick={() => goView(target)}
      className={
        'relative w-full rounded-md px-3 py-2.5 text-left text-sm font-medium transition-all duration-200 '
        + (view === target
          ? 'border-l-[3px] border-l-vermilion bg-vermilion-light/30 pl-[9px] text-vermilion'
          : 'border-l-[3px] border-l-transparent pl-[9px] text-sumi-dim hover:bg-paper-base hover:text-sumi')
      }
    >
      <span className="flex items-center gap-3">
        {icon}
        {sidebarOpen && <span>{label}</span>}
      </span>
    </button>
  );

  if (!authCheck) {
    return (
      <div className="flex h-dvh w-screen items-center justify-center bg-paper-base">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-paper-border border-t-vermilion" />
      </div>
    );
  }

  return (
    <div className="flex h-dvh w-screen overflow-hidden bg-paper-base text-sumi">
      <aside
        className={
          'flex shrink-0 flex-col border-r border-paper-border bg-paper-surface transition-all duration-300 '
          + (sidebarOpen ? 'w-[220px]' : 'w-14')
          + (isMobile && view === 'editor' ? ' hidden' : '')
        }
      >
        <div className="flex h-14 items-center justify-between border-b border-paper-border px-3">
          {sidebarOpen && (
            <span className="flex items-center gap-1.5 font-display text-base font-bold tracking-wide text-vermilion">
              <Sparkles size={15} />
              ArtVerse
            </span>
          )}
          <button
            type="button"
            onClick={() => setSidebarOpen((prev) => !prev)}
            className="ml-auto text-sumi-faint transition-colors hover:text-sumi-dim"
            aria-label="Toggle sidebar"
          >
            {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
          </button>
        </div>

        {sidebarOpen && <div className="brush-divider mx-3 mt-0" />}

        <nav className="flex flex-1 flex-col gap-0.5 px-2 py-3">
          {navItem(<Sparkles size={18} />, '创作助手', 'home')}
          {navItem(<Globe size={18} />, '作品广场', 'square')}
          {navItem(<BookOpenText size={18} />, '故事工作区', 'workspace')}
          {navItem(<FileText size={18} />, '作品管理', 'myworks')}
          {navItem(<Paintbrush size={18} />, 'AI 生图', 'imagegen')}
        </nav>

        <div className="flex flex-col gap-0.5 border-t border-paper-border px-2 py-3">
          {authenticated ? (
            <>
              {navItem(<KeyRound size={18} />, 'API 设置', 'settings')}
              <button
                type="button"
                onClick={() => { void handleLogout(); }}
                className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim transition-colors hover:bg-vermilion-light/30 hover:text-vermilion"
              >
                <LogOut size={18} />
                {sidebarOpen && <span>退出登录</span>}
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={() => requireLogin()}
              className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim transition-colors hover:bg-paper-base hover:text-sumi"
            >
              <LogIn size={18} />
              {sidebarOpen && <span>登录</span>}
            </button>
          )}
        </div>
      </aside>

      <div className="flex min-h-0 flex-1 flex-col">
        {view === 'home' && <MangaAgentPage onCreateStory={openWorkspaceCreateStory} />}
        {view === 'square' && <SquarePage />}
        {view === 'workspace' && (
          <HomePage
            onSelectStory={loadEditor}
            createStorySignal={workspaceCreateSignal}
            onCreateStorySignalConsumed={consumeWorkspaceCreateSignal}
          />
        )}
        {view === 'editor' && activeStoryId !== null && (
          <WorkspaceEditor storyId={activeStoryId} onBack={leaveEditor} />
        )}
        {view === 'imagegen' && <ImageGenPage />}
        {view === 'myworks' && <MyWorksPage />}
        {view === 'settings' && <ApiSettingsPage />}
      </div>

      {loginOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-sumi/30 backdrop-blur-sm"
          onClick={() => setLoginOpen(false)}
        >
          <div className="w-full max-w-sm animate-fade-in" onClick={(event) => event.stopPropagation()}>
            <LoginPage
              variant="modal"
              message={loginMessage}
              onCancel={() => setLoginOpen(false)}
              onAuthSuccess={handleAuthSuccess}
            />
          </div>
        </div>
      )}
    </div>
  );
}
