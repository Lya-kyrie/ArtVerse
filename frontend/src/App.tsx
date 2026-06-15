import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Plus, BookOpenText, Trash2, MessageSquare, Image, PanelLeftClose, PanelLeftOpen, KeyRound, ExternalLink, X, LogOut, Globe, Paintbrush, FileText } from 'lucide-react';
import ChatPanel from './components/ChatPanel';
import MangaPanel from './components/MangaPanel';
import HomePage from './components/HomePage';
import LoginPage from './components/LoginPage';
import SquarePage from './components/SquarePage';
import ImageGenPage from './components/ImageGenPage';
import MyWorksPage from './components/MyWorksPage';
import {
  listChapters, createNextChapter, deleteChapter, getChapter,
  type Story, type Chapter,
  getApiKeySettings, saveApiKeySettings, clearApiKeySettings, saveUserApiKey,
  DEEPSEEK_USAGE_URL, IMAGE2_CONSOLE_URL,
  isAuthenticated, logoutUser,
} from './api';

type View = 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks';
type MobileTab = 'chat' | 'manga';

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';
const MOBILE_BREAKPOINT = 1024;

function useIsMobile() {
  const read = () => navigator.maxTouchPoints>0||window.matchMedia('(any-pointer:coarse)').matches||window.matchMedia('(max-width:'+MOBILE_BREAKPOINT+'px)').matches||window.matchMedia('(pointer:coarse)').matches;
  const [m, setM] = useState(read);
  useEffect(()=>{const w=window.matchMedia('(max-width:'+MOBILE_BREAKPOINT+'px)');const t=window.matchMedia('(pointer:coarse)');const a=window.matchMedia('(any-pointer:coarse)');let f=0;const s=()=>{cancelAnimationFrame(f);f=requestAnimationFrame(()=>setM(read()))};w.addEventListener('change',s);t.addEventListener('change',s);a.addEventListener('change',s);window.addEventListener('resize',s);return()=>{cancelAnimationFrame(f);w.removeEventListener('change',s);t.removeEventListener('change',s);a.removeEventListener('change',s);window.removeEventListener('resize',s)}},[]);
  return m;
}

function ApiKeySettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [dk,setDk]=useState('');const [ik,setIk]=useState('');const [ck,setCk]=useState('');
  useEffect(()=>{if(!open)return;const s=getApiKeySettings();setDk(s.deepseekApiKey);setIk(s.imageApiKey);setCk(s.cozeApiKey)},[open]);
  if(!open)return null;
  const handleSave=async()=>{saveApiKeySettings({deepseekApiKey:dk,imageApiKey:ik,cozeApiKey:ck});const sync=async(p:string,k:string)=>{if(!k)return;try{await saveUserApiKey(p,k)}catch{}};await Promise.all([sync('deepseek',dk),sync('image2',ik),sync('coze',ck)]);onClose()};
  return(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-700 rounded-2xl shadow-2xl w-full max-w-md p-6 m-4 space-y-5" onClick={e=>e.stopPropagation()}>
        <div className="flex items-center justify-between"><h2 className="text-lg font-semibold text-white flex items-center gap-2"><KeyRound size={18} className="text-amber-400"/>API Keys</h2><button onClick={onClose} className="text-gray-500 hover:text-gray-300"><X size={18}/></button></div>
        <div className="space-y-4">
          <div><label className="block text-sm text-gray-400 mb-1">DeepSeek</label><input type="password" value={dk} onChange={e=>setDk(e.target.value)} placeholder="sk-..." className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-amber-500"/><a href={DEEPSEEK_USAGE_URL} target="_blank" rel="noopener" className="inline-flex items-center gap-1 text-xs text-amber-500 mt-1"><ExternalLink size={10}/>Get Key</a></div>
          <div><label className="block text-sm text-gray-400 mb-1">Image</label><input type="password" value={ik} onChange={e=>setIk(e.target.value)} placeholder="sk-..." className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-amber-500"/><a href={IMAGE2_CONSOLE_URL} target="_blank" rel="noopener" className="inline-flex items-center gap-1 text-xs text-amber-500 mt-1"><ExternalLink size={10}/>Get Key</a></div>
          <div><label className="block text-sm text-gray-400 mb-1">Coze</label><input type="password" value={ck} onChange={e=>setCk(e.target.value)} placeholder="pat-..." className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-amber-500"/></div>
        </div>
        <div className="flex justify-between items-center pt-2">
          <button onClick={()=>{if(!confirm('Clear all?'))return;clearApiKeySettings();setDk('');setIk('');setCk('')}} disabled={!dk&&!ik&&!ck} className="text-xs text-red-400 hover:text-red-300 disabled:opacity-30">Clear All</button>
          <div className="flex gap-2"><button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-gray-200">Cancel</button><button onClick={handleSave} className="px-4 py-2 text-sm font-medium rounded-lg bg-amber-600 hover:bg-amber-500 text-white">Save</button></div>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  const isMobile = useIsMobile();
  const [authenticated, setAuthenticated] = useState(false);
  const [authCheck, setAuthCheck] = useState(false);
  const [view, setView] = useState<View>('workspace');
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [activeStoryId, setActiveStoryId] = useState<number|null>(null);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [stories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [currentChapter, setCurrentChapter] = useState<Chapter|null>(null);
  const [creatingChapter, setCreatingChapter] = useState(false);
  const [mobileTab, setMobileTab] = useState<MobileTab>('chat');

  useEffect(()=>{setAuthenticated(isAuthenticated());setAuthCheck(true)},[]);
  if(!authCheck)return<div className="h-dvh w-screen bg-gray-950 flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-violet-400"/></div>;
  if(!authenticated)return<LoginPage onAuthSuccess={()=>setAuthenticated(true)}/>;

  const loadChapters=async(storyId:number)=>{try{const chs=await listChapters(storyId);setChapters(chs);const idx=Math.min(Number(localStorage.getItem(LS_CHAPTER_IDX)||'0'),chs.length-1);setCurrentIdx(idx);if(chs.length>0){const ch=await getChapter(chs[idx].id);setCurrentChapter(ch);localStorage.setItem(LS_CHAPTER_ID,String(chs[idx].id))}}catch{}};
  const loadEditor=async(storyId:number)=>{setActiveStoryId(storyId);localStorage.setItem(LS_STORY_ID,String(storyId));setView('editor');await loadChapters(storyId)};
  const unloadEditor=()=>{setActiveStoryId(null);setChapters([]);setCurrentChapter(null);localStorage.removeItem(LS_STORY_ID);localStorage.removeItem(LS_CHAPTER_ID);localStorage.removeItem(LS_CHAPTER_IDX)};
  const refreshCurrentChapter=async()=>{if(currentChapter){const ch=await getChapter(currentChapter.id);setCurrentChapter(ch)}};
  const handleChapterRefresh=async(chapterId:number)=>{try{const ch=await getChapter(chapterId);setCurrentChapter(ch);const chs=await listChapters(activeStoryId!);setChapters(chs)}catch{}};
  const setChapterByIndex=async(idx:number)=>{if(idx<0||idx>=chapters.length||!activeStoryId)return;setCurrentIdx(idx);const ch=await getChapter(chapters[idx].id);setCurrentChapter(ch);localStorage.setItem(LS_CHAPTER_ID,String(chapters[idx].id));localStorage.setItem(LS_CHAPTER_IDX,String(idx))};
  const handlePrev=()=>{if(currentIdx>0)setChapterByIndex(currentIdx-1)};
  const handleNext=async()=>{if(currentIdx<chapters.length-1){setChapterByIndex(currentIdx+1)}else if(activeStoryId){setCreatingChapter(true);try{await createNextChapter(activeStoryId);const chs=await listChapters(activeStoryId);setChapters(chs);const idx=chs.length-1;setCurrentIdx(idx);setCurrentChapter(await getChapter(chs[idx].id));localStorage.setItem(LS_CHAPTER_ID,String(chs[idx].id));localStorage.setItem(LS_CHAPTER_IDX,String(idx))}catch(e:any){alert('Failed: '+e.message)}finally{setCreatingChapter(false)}}};
  const handleDelete=async()=>{if(!currentChapter||chapters.length<=1||!activeStoryId)return;if(!confirm('Delete chapter?'))return;try{await deleteChapter(currentChapter.id);const chs=await listChapters(activeStoryId);setChapters(chs);const idx=Math.min(currentIdx,chs.length-1);setCurrentIdx(idx);if(chs.length>0)setCurrentChapter(await getChapter(chs[idx].id))}catch(e:any){alert('Failed: '+e.message)}};

  const navItem=(icon:React.ReactNode,label:string,v:View)=>(
    <button onClick={()=>{if(view==='editor')unloadEditor();setView(v)}}
      className={'w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors '+(view===v?'bg-violet-600/20 text-violet-300 border border-violet-500/30':'text-gray-400 hover:text-gray-200 hover:bg-gray-800/50')}>
      {icon}{sidebarOpen&&<span>{label}</span>}
    </button>
  );

  return (
    <div className="h-dvh w-screen bg-gray-950 text-gray-100 flex overflow-hidden">
      <aside className={'shrink-0 border-r border-gray-800 bg-gray-950 flex flex-col transition-all duration-200 '+(sidebarOpen?'w-48':'w-14')+' '+(isMobile&&view==='editor'?'hidden':'')}>
        <div className="flex items-center justify-between px-3 h-14 border-b border-gray-800">
          {sidebarOpen&&<span className="text-sm font-bold text-violet-400 tracking-wide">ArtVerse</span>}
          <button onClick={()=>setSidebarOpen(!sidebarOpen)} className="text-gray-500 hover:text-gray-300 ml-auto">{sidebarOpen?<PanelLeftClose size={16}/>:<PanelLeftOpen size={16}/>}</button>
        </div>
        <nav className="flex-1 flex flex-col gap-1 px-2 py-3">
          {navItem(<Globe size={18}/>,'Square','square')}
          {navItem(<BookOpenText size={18}/>,'Workspace','workspace')}
          {navItem(<FileText size={18}/>,'My Works','myworks')}
          {navItem(<Paintbrush size={18}/>,'Image Gen','imagegen')}
        </nav>
        <div className="px-2 py-3 border-t border-gray-800 flex flex-col gap-1">
          <button onClick={()=>setSettingsOpen(true)} className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-400 hover:text-gray-200 hover:bg-gray-800/50"><KeyRound size={18}/>{sidebarOpen&&<span>Settings</span>}</button>
          <button onClick={()=>{logoutUser();setAuthenticated(false)}} className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-400 hover:text-red-400 hover:bg-gray-800/50"><LogOut size={18}/>{sidebarOpen&&<span>Logout</span>}</button>
        </div>
      </aside>

      <div className="flex-1 flex flex-col min-w-0">
        {view==='square'&&<SquarePage/>}
        {view==='myworks'&&<MyWorksPage/>}
        {view==='imagegen'&&<ImageGenPage/>}
        {view==='workspace'&&<HomePage onSelectStory={(story)=>loadEditor(story.id)}/>}
        {view==='editor'&&(
          <div className="flex-1 flex flex-col min-h-0">
            <header className="h-14 border-b border-gray-800 flex items-center gap-3 px-4 shrink-0 bg-gray-950/80">
              <button onClick={()=>{unloadEditor();setView('workspace')}} className="text-gray-400 hover:text-gray-200 flex items-center gap-1"><ChevronLeft size={18}/>{!isMobile&&<span className="text-sm">Back</span>}</button>
              <span className="text-sm text-gray-400 truncate">{stories.find((s:Story)=>s.id===activeStoryId)?.title||''}</span>
            </header>
            {isMobile&&chapters.length>0&&(
              <div className="flex gap-1 overflow-x-auto border-b border-gray-800 bg-gray-950 px-2 py-2 shrink-0">
                {chapters.map((ch:Chapter,idx:number)=>(<button key={ch.id} onClick={()=>setChapterByIndex(idx)} className={'shrink-0 rounded-full border px-3 py-1.5 text-xs transition-colors '+(ch.id===currentChapter?.id?'border-violet-500 bg-violet-600/20 text-violet-200':'border-gray-800 bg-gray-900 text-gray-500 hover:text-gray-300')}>Ch.{ch.chapter_number}</button>))}
              </div>
            )}
            {isMobile&&(
              <div className="flex border-b border-gray-800 bg-gray-950">
                <button onClick={()=>setMobileTab('chat')} className={'flex-1 flex items-center justify-center gap-1.5 py-2.5 text-xs font-medium '+(mobileTab==='chat'?'text-amber-400 border-b-2 border-amber-400 bg-gray-900/50':'text-gray-500 hover:text-gray-300')}><MessageSquare size={14}/>Chat</button>
                <button onClick={()=>setMobileTab('manga')} className={'flex-1 flex items-center justify-center gap-1.5 py-2.5 text-xs font-medium '+(mobileTab==='manga'?'text-amber-400 border-b-2 border-amber-400 bg-gray-900/50':'text-gray-500 hover:text-gray-300')}><Image size={14}/>Manga</button>
              </div>
            )}
            {isMobile?(
              <main className="flex-1 min-h-0">
                <div className={'h-full '+(mobileTab==='chat'?'':'hidden')}><ChatPanel chapter={currentChapter} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh}/></div>
                <div className={'h-full '+(mobileTab==='manga'?'':'hidden')}><MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh}/></div>
              </main>
            ):(
              <main className="flex-1 flex min-h-0">
                <div className="w-1/2 border-r border-gray-800"><ChatPanel chapter={currentChapter} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh}/></div>
                <div className="w-1/2"><MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh}/></div>
              </main>
            )}
            <footer className="h-14 border-t border-gray-800 flex items-center justify-center gap-2 md:gap-4 shrink-0 bg-gray-950/80 backdrop-blur-sm px-2">
              <button onClick={handlePrev} disabled={currentIdx===0} className="flex items-center gap-1 px-3 md:px-5 py-2 text-sm font-medium rounded-lg bg-gray-800 hover:bg-gray-700 text-gray-300 disabled:opacity-30 disabled:cursor-not-allowed"><ChevronLeft size={16}/>{!isMobile&&'Prev'}</button>
              <button onClick={handleDelete} disabled={!currentChapter||chapters.length<=1} className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg bg-red-900/50 hover:bg-red-800 text-red-300 disabled:opacity-30 disabled:cursor-not-allowed"><Trash2 size={14}/></button>
              <div className="flex items-center gap-1 text-xs text-gray-600">{chapters.map((ch:Chapter,i:number)=>(<button key={ch.id} onClick={()=>setChapterByIndex(i)} className={'w-2 h-2 rounded-full '+(i===currentIdx?'bg-violet-500':'bg-gray-700 hover:bg-gray-600')}/>))}</div>
              <button onClick={handleNext} disabled={creatingChapter} className="flex items-center gap-1 px-3 md:px-5 py-2 text-sm font-medium rounded-lg bg-violet-600 hover:bg-violet-500 text-white disabled:opacity-40 disabled:cursor-not-allowed">{currentIdx===chapters.length-1?<><Plus size={16}/>{creatingChapter?'...':(isMobile?'New':'Next(New)')}</>:<>{!isMobile&&'Next'}<ChevronRight size={16}/></>}</button>
            </footer>
          </div>
        )}
      </div>
      <ApiKeySettingsModal open={settingsOpen} onClose={()=>setSettingsOpen(false)}/>
    </div>
  );
}
