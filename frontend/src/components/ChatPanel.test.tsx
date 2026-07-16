import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatPanel from './ChatPanel';
import {
  commitNovelContentProposal,
  createNovelContentProposal,
  updateNovelContentProposal,
  type Chapter,
} from '../api';

vi.mock('./MarkdownRenderer', () => ({ default: ({ content }: { content: string }) => <div>{content}</div> }));
vi.mock('./ModelSwitcher', () => ({ default: () => <div /> }));
vi.mock('./InlineConversationTitle', () => ({ default: ({ title }: { title: string }) => <span>{title}</span> }));

vi.mock('../api', () => ({
  API_KEY_CHANGE_EVENT: 'api-key-change',
  chatStream: vi.fn(),
  commitNovelContentProposal: vi.fn(),
  createNovelContentProposal: vi.fn(),
  getPrimaryProviderModel: () => 'model-a',
  getProviderModelOptions: () => ['model-a'],
  listAiConversations: vi.fn().mockResolvedValue([{ conversationId: 'conv-1', title: 'Story Chat' }]),
  listNovelRevisions: vi.fn().mockResolvedValue([]),
  renameAiConversation: vi.fn(),
  restoreNovelRevision: vi.fn(),
  saveNovelContent: vi.fn(),
  updateNovelContentProposal: vi.fn(),
}));

const chapter: Chapter = {
  id: 7,
  story_id: 3,
  chapter_number: 1,
  version: 4,
  novel_content: '当前原文',
  content_source: null,
  created_at: '2026-07-16T00:00:00Z',
  images: [],
  messages: [
    { id: 1, role: 'user', content: '帮我改', completion_status: 'complete', created_at: '2026-07-16T00:00:00Z' },
    { id: 2, role: 'assistant', content: '普通 AI 回复', completion_status: 'complete', created_at: '2026-07-16T00:00:01Z' },
  ],
};

describe('ChatPanel novel proposal flow', () => {
  beforeEach(() => {
    vi.mocked(createNovelContentProposal).mockReset();
    vi.mocked(updateNovelContentProposal).mockReset();
    vi.mocked(commitNovelContentProposal).mockReset();
    vi.mocked(createNovelContentProposal).mockResolvedValue({
      proposal_id: 'proposal-1',
      content: '正文候选',
      content_hash: 'hash-1',
      base_version: 4,
      through_message_id: 2,
      status: 'draft',
    });
    vi.mocked(updateNovelContentProposal).mockResolvedValue({
      proposal_id: 'proposal-1',
      content: '编辑后正文',
      content_hash: 'hash-2',
      base_version: 4,
      through_message_id: 2,
      status: 'draft',
    });
    vi.mocked(commitNovelContentProposal).mockResolvedValue({
      proposal_id: 'proposal-1',
      changed: true,
      chapter_version: 5,
      content_hash: 'hash-2',
      status: 'committed',
    });
  });

  afterEach(() => cleanup());

  it('opens editable preview instead of directly saving an assistant reply', async () => {
    render(<ChatPanel chapter={chapter} />);

    fireEvent.click(await screen.findByRole('button', { name: '整理为正文草稿' }));

    expect(createNovelContentProposal).toHaveBeenCalledWith(7, 'conv-1', 2, 4, 'model-a');
    expect(await screen.findByRole('dialog', { name: '正文草稿确认' })).toBeTruthy();
    expect(commitNovelContentProposal).not.toHaveBeenCalled();
  });

  it('updates edited proposal before committing replacement', async () => {
    render(<ChatPanel chapter={chapter} onChapterRefresh={vi.fn().mockResolvedValue(undefined)} />);
    fireEvent.click(await screen.findByRole('button', { name: '整理为正文草稿' }));
    const editor = await screen.findByDisplayValue('正文候选');
    fireEvent.change(editor, { target: { value: '编辑后正文' } });

    fireEvent.click(screen.getByRole('button', { name: '确认替换原文（可从历史恢复）' }));

    await waitFor(() => expect(updateNovelContentProposal).toHaveBeenCalledWith(7, 'proposal-1', '编辑后正文', 'hash-1'));
    expect(commitNovelContentProposal).toHaveBeenCalledWith(7, 'proposal-1', 4, 'hash-2');
  });

  it('does not show proposal action for partial assistant messages', async () => {
    render(<ChatPanel chapter={{
      ...chapter,
      messages: [{ id: 2, role: 'assistant', content: '半截回复', completion_status: 'partial', created_at: '2026-07-16T00:00:01Z' }],
    }} />);

    await screen.findByText('半截回复');
    expect(screen.queryByRole('button', { name: '整理为正文草稿' })).toBeNull();
  });
});
