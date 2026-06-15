import { useState } from 'react';
import { Paintbrush, Loader2, Trash2 } from 'lucide-react';
import { generateImage, listImageGenHistory, deleteImageGenRecord, imageGenUrl, type ImageGenRecord } from '../api';

export default function ImageGenPage() {
  const [prompt, setPrompt] = useState('');
  const [generating, setGenerating] = useState(false);
  const [history, setHistory] = useState<ImageGenRecord[]>([]);
  const [loaded, setLoaded] = useState(false);

  const loadHistory = async () => { try { const r = await listImageGenHistory(0, 50); setHistory(r.content); } catch {} finally { setLoaded(true); } };
  if (!loaded) { loadHistory(); return <div className="flex-1 flex items-center justify-center"><Loader2 size={28} className="animate-spin text-violet-400" /></div>; }

  const handleGenerate = async () => { if (!prompt.trim()) return; setGenerating(true); try { const r = await generateImage(prompt.trim()); setHistory(prev => [r, ...prev]); setPrompt(''); } catch(e:any){ alert('Generate failed: '+(e.message||'Unknown')); } finally { setGenerating(false); } };

  const handleDelete = async (id: number) => { try { await deleteImageGenRecord(id); setHistory(prev => prev.filter(h => h.id !== id)); } catch(e:any){ alert('Delete failed: '+(e.message||'Unknown')); } };

  return (
    <div className="flex-1 flex flex-col min-h-0">
      <div className="px-6 py-4 border-b border-gray-800 shrink-0">
        <h2 className="text-xl font-bold text-white flex items-center gap-2"><Paintbrush size={22} className="text-violet-400" /> Image Generation</h2>
      </div>
      <div className="p-6 border-b border-gray-800 shrink-0">
        <div className="flex gap-2">
          <input value={prompt} onChange={e => setPrompt(e.target.value)} onKeyDown={e => e.key==='Enter'&&handleGenerate()} placeholder="Enter prompt..." className="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-4 py-2 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-violet-500" />
          <button onClick={handleGenerate} disabled={generating||!prompt.trim()} className="px-4 py-2 bg-violet-600 hover:bg-violet-500 text-white text-sm font-medium rounded-lg disabled:opacity-40 transition-colors">{generating?<Loader2 size={14} className="animate-spin"/>:'Generate'}</button>
        </div>
      </div>
      <div className="flex-1 overflow-y-auto p-6">
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {history.map(r => (
            <div key={r.id} className="relative group border border-gray-800 rounded-xl overflow-hidden">
              <img src={imageGenUrl(r.image_url)} alt={r.prompt} className="w-full aspect-square object-cover" />
              <button onClick={() => handleDelete(r.id)} className="absolute top-2 right-2 p-1.5 rounded-lg bg-red-600/80 hover:bg-red-500 text-white opacity-0 group-hover:opacity-100 transition-opacity"><Trash2 size={14} /></button>
              <div className="p-2"><p className="text-xs text-gray-400 line-clamp-2">{r.prompt}</p></div>
            </div>
          ))}
          {history.length===0 && <p className="col-span-full text-center text-gray-600 py-12">No generated images yet</p>}
        </div>
      </div>
    </div>
  );
}