import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatPanel from './ChatPanel';
import {
  createAiConversation,
  getOpenStoryChatConversationRun,
  getStoryChatRunArtifacts,
  listAiConversations,
  runStoryChatAgUiStream,
  resumeStoryChatAgUiStream,
  type AiConversationSummary,
  type Chapter,
  type MangaAgentArtifact,
  type MangaAgentRunSnapshot,
} from '../api';

vi.mock('./MarkdownRenderer', () => ({ default: ({ content }: { content: string }) => <div>{content}</div> }));
vi.mock('./ModelSwitcher', () => ({ default: () => <div /> }));
vi.mock('./InlineConversationTitle', () => ({ default: ({ title }: { title: string }) => <span>{title}</span> }));

vi.mock('../api', () => ({
  API_KEY_CHANGE_EVENT: 'api-key-change',
  cancelStoryChatConversationRun: vi.fn(),
  createAiConversation: vi.fn().mockResolvedValue({ conversationId: 'conv-created', title: 'Story Chat' }),
  getOpenStoryChatConversationRun: vi.fn().mockResolvedValue(null),
  getPrimaryProviderModel: () => 'model-a',
  getProviderModelOptions: () => ['model-a'],
  getStoryChatRunArtifacts: vi.fn().mockResolvedValue([]),
  listAiConversations: vi.fn().mockResolvedValue([{ conversationId: 'conv-1', title: 'Story Chat' }]),
  listNovelRevisions: vi.fn().mockResolvedValue([]),
  renameAiConversation: vi.fn(),
  restoreNovelRevision: vi.fn(),
  resumeStoryChatAgUiStream: vi.fn(() => new AbortController()),
  runStoryChatAgUiStream: vi.fn(() => new AbortController()),
  saveNovelContent: vi.fn(),
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

const existingConversation = {
  conversationId: 'conv-1',
  title: 'Story Chat',
} as AiConversationSummary;

const createdConversation = {
  conversationId: 'conv-created',
  title: 'Story Chat',
} as AiConversationSummary;

const waitingRun = {
  requestId: 'run-1',
  status: 'WAITING_USER',
  events: [],
} as MangaAgentRunSnapshot;

describe('ChatPanel story chat safe draft flow', () => {
  beforeEach(() => {
    Object.defineProperty(window.HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
      writable: true,
    });
    vi.mocked(getOpenStoryChatConversationRun).mockResolvedValue(null);
    vi.mocked(getStoryChatRunArtifacts).mockResolvedValue([]);
    vi.mocked(listAiConversations).mockResolvedValue([existingConversation]);
    vi.mocked(createAiConversation).mockResolvedValue(createdConversation);
    vi.mocked(runStoryChatAgUiStream).mockClear();
    vi.mocked(resumeStoryChatAgUiStream).mockClear();
  });

  afterEach(() => cleanup());

  it('does not show the retired proposal action', async () => {
    render(<ChatPanel chapter={chapter} />);

    await screen.findByText('普通 AI 回复');
    expect(screen.queryByRole('button', { name: '整理为正文草稿' })).toBeNull();
    expect(screen.queryByRole('button', { name: '查看漫画 / 生成分镜' })).toBeNull();
  });

  it('restores a waiting novel draft card from run artifacts', async () => {
    vi.mocked(getOpenStoryChatConversationRun).mockResolvedValue(waitingRun);
    vi.mocked(getStoryChatRunArtifacts).mockResolvedValue([{
      artifactId: 'artifact-1',
      requestId: 'run-1',
      type: 'NOVEL_CONTENT_DRAFT',
      status: 'VALIDATED',
      schemaVersion: '1',
      checksum: 'hash-1',
      payload: {
        content: '原片段加续写',
        content_hash: 'hash-1',
        base_version: 4,
        current_word_count: 0,
        word_count: 6,
      },
      evaluation: {},
    } satisfies MangaAgentArtifact]);

    render(<ChatPanel chapter={chapter} />);

    expect(await screen.findByText('小说原文草稿')).toBeTruthy();
    expect(screen.getByText('原片段加续写')).toBeTruthy();
  });

  it('confirms a restored draft through structured resume payload', async () => {
    vi.mocked(getOpenStoryChatConversationRun).mockResolvedValue(waitingRun);
    vi.mocked(getStoryChatRunArtifacts).mockResolvedValue([{
      artifactId: 'artifact-1',
      requestId: 'run-1',
      type: 'NOVEL_CONTENT_DRAFT',
      status: 'VALIDATED',
      schemaVersion: '1',
      checksum: 'hash-1',
      payload: { content: '草稿正文', content_hash: 'hash-1', base_version: 4, word_count: 4 },
      evaluation: {},
    } satisfies MangaAgentArtifact]);

    render(<ChatPanel chapter={chapter} />);
    fireEvent.click(await screen.findByRole('button', { name: '确认写入' }));

    await waitFor(() => expect(resumeStoryChatAgUiStream).toHaveBeenCalledWith(
      7,
      'conv-1',
      'run-1',
      'confirm',
      'artifact-1',
      expect.any(Function),
      'model-a',
    ));
  });

  it('creates a chapter story chat conversation before sending when none exists', async () => {
    vi.mocked(listAiConversations).mockResolvedValue([]);

    render(<ChatPanel chapter={{ ...chapter, messages: [] }} />);
    const textbox = await screen.findByPlaceholderText(/AI/);
    fireEvent.change(textbox, { target: { value: 'please continue and save' } });
    fireEvent.keyDown(textbox, { key: 'Enter', shiftKey: false });

    await waitFor(() => expect(createAiConversation).toHaveBeenCalledWith('STORY_CHAT', undefined, 7));
    expect(runStoryChatAgUiStream).toHaveBeenCalledWith(
      7,
      'conv-created',
      'please continue and save',
      expect.any(String),
      expect.any(Function),
      'model-a',
    );
  });
});
