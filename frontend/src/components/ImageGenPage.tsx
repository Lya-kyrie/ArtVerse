import { useEffect, useRef, useState } from 'react';
import { Paintbrush, Loader2, Trash2, Send, ImagePlus, X } from 'lucide-react';
import { generateImage, listImageGenHistory, deleteImageGenRecord, imageGenUrl, type ImageGenRecord } from '../api';

interface Message {
  id: string;
  type: 'user' | 'ai';
  prompt?: string;
  refThumbnails?: string[];
  record?: ImageGenRecord;
}

export default function ImageGenPage() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [prompt, setPrompt] = useState('');
  const [refFiles, setRefFiles] = useState<{ file: File; preview: string }[]>([]);
  const [generating, setGenerating] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    (async () => {
      try {
        const r = await listImageGenHistory(0, 50);
        const msgs: Message[] = [];
        // Reverse so oldest first
        const reversed = [...r.content].reverse();
        for (const record of reversed) {
          msgs.push({ id: 'u-' + record.id, type: 'user', prompt: record.prompt });
          msgs.push({ id: 'a-' + record.id, type: 'ai', record });
        }
        setMessages(msgs);
      } catch { /* empty history ok */ }
      setLoading(false);
    })();
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, generating]);

  const handleAddRef = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;
    const remaining = 3 - refFiles.length;
    const toAdd = Math.min(files.length, remaining);
    const newRefs: { file: File; preview: string }[] = [];
    for (let i = 0; i < toAdd; i++) {
      const f = files[i];
      if (f.size > 10 * 1024 * 1024) { alert(f.name + ': max 10MB'); continue; }
      newRefs.push({ file: f, preview: URL.createObjectURL(f) });
    }
    setRefFiles(prev => [...prev, ...newRefs].slice(0, 3));
    e.target.value = '';
  };

  const removeRef = (idx: number) => {
    setRefFiles(prev => {
      URL.revokeObjectURL(prev[idx].preview);
      return prev.filter((_, i) => i !== idx);
    });
  };

  const handleSend = async () => {
    if (!prompt.trim() && refFiles.length === 0) return;
    const userMsg: Message = {
      id: 'u-temp-' + Date.now(),
      type: 'user',
      prompt: prompt.trim() || '(image only)',
      refThumbnails: refFiles.map(f => f.preview),
    };
    setMessages(prev => [...prev, userMsg]);
    const promptText = prompt.trim();
    setPrompt('');
    setRefFiles([]);
    setGenerating(true);

    try {
      // Convert ref files to base64
      const refBase64: string[] = [];
      for (const rf of refFiles) {
        const b64 = await new Promise<string>((resolve) => {
          const reader = new FileReader();
          reader.onload = () => resolve((reader.result as string).split(',')[1]);
          reader.readAsDataURL(rf.file);
        });
        refBase64.push(b64);
      }
      const record = await generateImage(promptText, refBase64.length > 0 ? refBase64 : undefined);
      const aiMsg: Message = { id: 'a-' + record.id, type: 'ai', record };
      setMessages(prev => [...prev, aiMsg]);
    } catch (e: any) {
      const errMsg: Message = { id: 'err-' + Date.now(), type: 'ai', prompt: '生成失败: ' + (e.message || '未知错误') };
      setMessages(prev => [...prev, errMsg]);
    } finally {
      setGenerating(false);
    }
  };

  const handleDelete = async (id: number, msgId: string) => {
    try {
      await deleteImageGenRecord(id);
      setMessages(prev => prev.filter(m => m.id !== 'u-' + id && m.id !== 'a-' + id && m.id !== msgId));
    } catch (e: any) {
      alert('删除失败: ' + (e.message || '未知错误'));
    }
  };

  if (loading) return <div className="flex-1 flex items-center justify-center"><Loader2 size={28} className="animate-spin text-violet-400" /></div>;

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* Header */}
      <div className="px-4 md:px-6 py-3 border-b border-gray-800 shrink-0">
        <h2 className="text-lg font-semibold text-white flex items-center gap-2">
          <Paintbrush size={20} className="text-violet-400" /> 生图
        </h2>
      </div>

      {/* Messages */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
        {messages.length === 0 && !generating && (
          <div className="flex items-center justify-center h-full text-gray-600">
            <div className="text-center">
              <Paintbrush size={48} className="mx-auto mb-3 opacity-30" />
              <p className="text-sm">输入提示词开始生图</p>
              <p className="text-xs mt-1 text-gray-700">支持上传参考图片</p>
            </div>
          </div>
        )}

        {messages.map((msg) => {
          if (msg.type === 'user') {
            return (
              <div key={msg.id} className="flex justify-end">
                <div className="max-w-[75%] bg-violet-600/20 border border-violet-500/30 rounded-2xl rounded-br-md px-4 py-3">
                  {/* Reference thumbnails */}
                  {msg.refThumbnails && msg.refThumbnails.length > 0 && (
                    <div className="flex gap-2 mb-2">
                      {msg.refThumbnails.map((src, i) => (
                        <img key={i} src={src} alt={'ref ' + (i + 1)} className="w-16 h-16 object-cover rounded-lg border border-violet-500/30" />
                      ))}
                    </div>
                  )}
                  <p className="text-sm text-white whitespace-pre-wrap break-words">{msg.prompt}</p>
                </div>
              </div>
            );
          }

          // AI message
          const record = msg.record;
          if (record) {
            return (
              <div key={msg.id} className="flex justify-start">
                <div className="max-w-[85%] bg-gray-800/60 border border-gray-700 rounded-2xl rounded-bl-md overflow-hidden group">
                  <img src={imageGenUrl(record.image_url)} alt={record.prompt} className="w-full" loading="lazy" />
                  <div className="px-3 py-2 flex items-center justify-between">
                    <p className="text-xs text-gray-400 truncate flex-1">{record.prompt}</p>
                    <button
                      onClick={() => handleDelete(record.id, msg.id)}
                      className="ml-2 p-1 text-gray-600 hover:text-red-400 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                      title="删除"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              </div>
            );
          }

          // Error message
          return (
            <div key={msg.id} className="flex justify-start">
              <div className="max-w-[75%] bg-red-900/20 border border-red-800/30 rounded-2xl rounded-bl-md px-4 py-2.5">
                <p className="text-sm text-red-400">{msg.prompt}</p>
              </div>
            </div>
          );
        })}

        {/* Generating indicator */}
        {generating && (
          <div className="flex justify-start">
            <div className="bg-gray-800/60 border border-gray-700 rounded-2xl rounded-bl-md px-4 py-3 flex items-center gap-2">
              <Loader2 size={16} className="animate-spin text-violet-400" />
              <span className="text-sm text-gray-400">生成中...</span>
            </div>
          </div>
        )}
      </div>

      {/* Input area */}
      <div className="px-4 py-3 border-t border-gray-800 bg-gray-950/80 shrink-0">
        {/* Reference image previews */}
        {refFiles.length > 0 && (
          <div className="flex gap-2 mb-3">
            {refFiles.map((rf, i) => (
              <div key={i} className="relative w-14 h-14 rounded-lg overflow-hidden border border-gray-700 shrink-0">
                <img src={rf.preview} alt={'ref ' + (i + 1)} className="w-full h-full object-cover" />
                <button onClick={() => removeRef(i)} className="absolute top-0 right-0 p-0.5 bg-black/70 text-white rounded-bl">
                  <X size={10} />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="flex items-center gap-2">
          {/* Upload reference */}
          <label className={'flex items-center justify-center w-9 h-9 rounded-lg border border-gray-700 text-gray-400 hover:text-violet-400 hover:border-violet-500 cursor-pointer transition-colors shrink-0 ' + (refFiles.length >= 3 ? 'opacity-40 pointer-events-none' : '')} title="上传参考图（最多3张）">
            <ImagePlus size={16} />
            <input type="file" accept="image/*" multiple onChange={handleAddRef} className="hidden" />
          </label>

          {/* Prompt input */}
          <input
            ref={inputRef}
            value={prompt}
            onChange={e => setPrompt(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); } }}
            placeholder="输入提示词描述你想要的画面... (Enter 发送)"
            disabled={generating}
            className="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-4 py-2.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-violet-500 disabled:opacity-50"
          />

          {/* Send button */}
          <button
            onClick={handleSend}
            disabled={generating || (!prompt.trim() && refFiles.length === 0)}
            className="flex items-center justify-center w-9 h-9 rounded-lg bg-violet-600 hover:bg-violet-500 text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
          >
            <Send size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}
