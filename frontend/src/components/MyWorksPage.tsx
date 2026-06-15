import { useEffect, useState } from 'react';
import { Globe, Send, Loader2, BookOpenText, FileText, CheckSquare, Square } from 'lucide-react';
import { listMyWorks, publishStory, updateChapterOrder, type MyWork, type MyWorkChapter } from '../api';

export default function MyWorksPage() {
  const [works, setWorks] = useState<MyWork[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [editChapters, setEditChapters] = useState<MyWorkChapter[]>([]);

  useEffect(() => {
    (async () => { setLoading(true); try { setWorks(await listMyWorks()); } catch(e:any){ console.error(e); } finally { setLoading(false); } })();
  }, []);

  const selectedWork = works.find(w => w.id === selectedId) || null;

  const selectWork = (id: number) => { setSelectedId(id); const w = works.find(x => x.id === id); if (w) setEditChapters(w.chapters.map(ch => ({ ...ch }))); };

  const toggleChapter = (chapterId: number) => { setEditChapters(prev => prev.map(ch => ch.id === chapterId ? { ...ch, is_published: !ch.is_published } : ch)); };
  const updateDisplayTitle = (chapterId: number, title: string) => { setEditChapters(prev => prev.map(ch => ch.id === chapterId ? { ...ch, display_title: title } : ch)); };
  const selectAll = () => setEditChapters(prev => prev.map(ch => ({ ...ch, is_published: true })));
  const deselectAll = () => setEditChapters(prev => prev.map(ch => ({ ...ch, is_published: false })));

  const handleSave = async () => { if (!selectedWork) return; setSaving(true); try { const sel = editChapters.filter(c => c.is_published).map(c => c.id); await publishStory(selectedWork.id, sel.length > 0, sel); await updateChapterOrder(selectedWork.id, editChapters.map((ch,i) => ({ chapter_id: ch.id, display_order: i, display_title: ch.display_title || undefined }))); const u = await listMyWorks(); setWorks(u); const r = u.find(w => w.id === selectedWork.id); if (r) setEditChapters(r.chapters.map(ch => ({ ...ch }))); } catch(e:any){ alert('Save failed: '+(e.message||'Unknown')); } finally { setSaving(false); } };

  if (loading) return <div className="flex-1 flex items-center justify-center"><Loader2 size={28} className="animate-spin text-violet-400" /></div>;

  return (
    <div className="flex-1 flex min-h-0">
      <div className="w-72 border-r border-gray-800 flex flex-col min-h-0 bg-gray-950/50">
        <div className="px-4 py-4 border-b border-gray-800 shrink-0">
          <h2 className="text-lg font-semibold text-white flex items-center gap-2"><BookOpenText size={20} className="text-violet-400" /> My Works</h2>
          <p className="text-xs text-gray-500 mt-1">{works.length} works</p>
        </div>
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {works.length === 0 ? <p className="text-sm text-gray-600 text-center py-8">No works</p> :
            works.map(work => (
              <button key={work.id} onClick={() => selectWork(work.id)}
                className={'w-full text-left px-3 py-3 rounded-lg transition-colors '+(selectedId===work.id ? 'bg-violet-600/20 border border-violet-500/50' : 'border border-transparent hover:bg-gray-800/50')}>
                <div className="flex items-center justify-between">
                  <span className="font-medium text-sm text-gray-200 truncate">{work.title}</span>
                  {work.is_published && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-green-900/50 text-green-400 border border-green-800 flex items-center gap-1 shrink-0 ml-2"><Globe size={10} /> Published</span>}
                </div>
                <p className="text-xs text-gray-600 truncate mt-0.5">{work.chapters.length} chapters</p>
              </button>
            ))}
        </div>
      </div>
      <div className="flex-1 flex flex-col min-h-0">
        {!selectedWork ? (
          <div className="flex-1 flex items-center justify-center text-gray-600"><div className="text-center"><FileText size={48} className="mx-auto mb-3 opacity-30" /><p className="text-sm">Select a work to manage publishing</p></div></div>
        ) : (
          <>
            <div className="flex items-center justify-between px-6 py-4 border-b border-gray-800 shrink-0">
              <div><h3 className="text-lg font-semibold text-white">{selectedWork.title}</h3><p className="text-xs text-gray-500">{selectedWork.is_published ? 'Published' : 'Draft'}{selectedWork.published_at ? ' / '+new Date(selectedWork.published_at).toLocaleDateString() : ''}</p></div>
              <span className="text-xs text-gray-500">{editChapters.filter(ch=>ch.is_published).length} of {editChapters.length} selected</span>
            </div>
            <div className="flex-1 overflow-y-auto p-6">
              <div className="flex items-center gap-3 mb-4">
                <button onClick={selectAll} className="text-xs text-gray-400 hover:text-violet-400 transition-colors flex items-center gap-1"><CheckSquare size={14} /> Select All</button>
                <button onClick={deselectAll} className="text-xs text-gray-400 hover:text-gray-300 transition-colors flex items-center gap-1"><Square size={14} /> Deselect All</button>
              </div>
              {editChapters.length === 0 ? <p className="text-sm text-gray-600 text-center py-8">No chapters</p> :
                <div className="space-y-2">
                  {editChapters.map((ch, idx) => (
                    <div key={ch.id} className={'flex items-center gap-3 p-3 rounded-lg border transition-colors '+(ch.is_published ? 'border-violet-500/30 bg-violet-500/5' : 'border-gray-800 bg-gray-900/30 hover:border-gray-700')}>
                      <label className="flex items-center cursor-pointer"><input type="checkbox" checked={ch.is_published} onChange={() => toggleChapter(ch.id)} className="w-4 h-4 rounded border-gray-600 bg-gray-800 text-violet-500 focus:ring-violet-500" /></label>
                      <span className="text-xs text-gray-500 w-16 shrink-0">Ch.{ch.chapter_number}</span>
                      <input value={ch.display_title || ''} onChange={e => updateDisplayTitle(ch.id, e.target.value)} placeholder={'Chapter '+ch.chapter_number} className="flex-1 bg-gray-800 border border-gray-700 rounded-md px-3 py-1.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-violet-500" />
                      <span className="text-xs text-gray-700 w-8 text-right">#{idx + 1}</span>
                    </div>
                  ))}
                </div>
              }
            </div>
            <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-gray-800 shrink-0">
              <button onClick={handleSave} disabled={saving} className="flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg bg-violet-600 hover:bg-violet-500 text-white disabled:opacity-40 transition-colors">{saving ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />} Save & Publish</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}