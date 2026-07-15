import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SquarePage from './SquarePage';
import { getSquareStoryDetail, type SquareStoryDetail } from '../api';

vi.mock('../api', () => ({
  coverImageUrl: (path: string | null | undefined) => (path ? `/static/manga/${path}` : null),
  getSquareStoryDetail: vi.fn(),
  listSquareStories: vi.fn(),
  mangaImageUrl: (path: string) => `/static/manga/${path}`,
}));

const novelDetail: SquareStoryDetail = {
  id: 7,
  format: 'novel',
  title: '测试小说',
  description: '用于验证章节阅读路由。',
  cover_url: '',
  manga_style: '',
  published_at: null,
  available_formats: ['novel'],
  chapters: [
    { id: 11, chapter_number: 1, display_title: '第一章', content: '第一章正文', content_count: 5, images: [] },
    { id: 12, chapter_number: 2, display_title: '第二章', content: '第二章正文', content_count: 5, images: [] },
  ],
};

const mangaDetail: SquareStoryDetail = {
  ...novelDetail,
  format: 'manga',
  available_formats: ['manga'],
  chapters: [
    {
      id: 21,
      chapter_number: 1,
      display_title: '第一话',
      content: null,
      content_count: 1,
      images: [{ id: 101, image_number: 1, image_url: 'chapter_21/panel_01.png' }],
    },
  ],
};

const mockedGetDetail = vi.mocked(getSquareStoryDetail);

function renderAt(hash: string) {
  window.history.replaceState(null, '', hash);
  return render(<SquarePage />);
}

describe('SquarePage chapter reader', () => {
  beforeEach(() => {
    localStorage.clear();
    mockedGetDetail.mockReset();
  });

  afterEach(() => cleanup());

  it('switches from a novel detail page to the selected chapter without another detail request', async () => {
    mockedGetDetail.mockResolvedValue(novelDetail);
    renderAt('#/square/novel/7');

    await screen.findByRole('heading', { name: '章节目录' });
    const chapterButtons = screen.getAllByRole('button', { name: /第二章/ });
    fireEvent.click(chapterButtons[chapterButtons.length - 1]);

    expect(await screen.findByText('第二章正文')).toBeTruthy();
    expect(window.location.hash).toBe('#/square/novel/7/chapter/12');
    expect(mockedGetDetail).toHaveBeenCalledTimes(1);
  });

  it('returns from the reader to the detail page when the chapter segment is removed', async () => {
    mockedGetDetail.mockResolvedValue(novelDetail);
    renderAt('#/square/novel/7/chapter/11');

    expect(await screen.findByText('第一章正文')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '详情' }));

    expect(await screen.findByRole('heading', { name: '章节目录' })).toBeTruthy();
    expect(window.location.hash).toBe('#/square/novel/7');
  });

  it('renders a manga chapter from a direct chapter link', async () => {
    mockedGetDetail.mockResolvedValue(mangaDetail);
    renderAt('#/square/manga/7/chapter/21');

    const image = await screen.findByRole('img', { name: '第一话第 1 页' });
    expect(image.getAttribute('src')).toBe('/static/manga/chapter_21/panel_01.png');
  });

  it('does not silently substitute the first chapter for an invalid chapter link', async () => {
    mockedGetDetail.mockResolvedValue(novelDetail);
    renderAt('#/square/novel/7/chapter/999');

    expect(await screen.findByText('章节不存在或尚未公开。')).toBeTruthy();
    expect(screen.queryByText('第一章正文')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '返回作品详情' }));
    await waitFor(() => expect(window.location.hash).toBe('#/square/novel/7'));
  });
});
