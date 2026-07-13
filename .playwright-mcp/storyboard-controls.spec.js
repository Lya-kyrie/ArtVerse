const { chromium } = require('D:/develop/NodeJS/node_cache/_npx/e41f203b7505f1fb/node_modules/playwright');

const story = {
  id: 1,
  title: '分镜状态验证小说',
  description: '验证章节设定组与分镜操作状态。',
  cover_image: null,
  created_at: '2026-07-13T10:00:00Z',
};

function chapter(id, number) {
  return {
    id,
    story_id: 1,
    chapter_number: number,
    novel_content: '夜雨中的车站，主角收到一封没有署名的信。',
    messages: [],
    images: [],
    status: 'DRAFT',
  };
}

async function mockApi(page, { existingStoryboard = false } = {}) {
  let groups = existingStoryboard
    ? [{ id: 7, name: '主角组', description: '主要角色设定', characters: [] }]
    : [];
  let selectedGroupId = existingStoryboard ? 7 : null;

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    let body = [];

    if (path === '/api/stories') body = [story];
    else if (path === '/api/stories/1/chapters') body = [chapter(11, 1)];
    else if (path === '/api/chapters/11') body = chapter(11, 1);
    else if (path === '/api/stories/1/characters') body = [];
    else if (path === '/api/stories/1/asset-groups' && method === 'GET') body = groups;
    else if (path === '/api/stories/1/asset-groups' && method === 'POST') {
      const created = { id: 7, name: '新设定组', description: '', characters: [] };
      groups = [created];
      body = created;
    } else if (path === '/api/asset-groups/7' && method === 'PUT') {
      const payload = request.postDataJSON();
      groups = [{ ...groups[0], ...payload, characters: [] }];
      body = groups[0];
    } else if (path === '/api/chapters/11/asset-group' && method === 'GET') {
      body = { groups, max: 4, selected_group_id: selectedGroupId };
    } else if (path === '/api/chapters/11/asset-group' && method === 'PUT') {
      selectedGroupId = request.postDataJSON().group_id;
      body = { groups, max: 4, selected_group_id: selectedGroupId };
    } else if (path === '/api/chapters/11/scenes') {
      body = { scenes: existingStoryboard ? ['第 1 页\n【第 1 格】雨夜车站，主角拆开信封。'] : [] };
    } else if (path === '/api/chapters/11/color-mode') body = { color_mode: 'bw' };
    else if (path === '/api/chapters/11/image-count') body = { image_count: 10 };
    else if (path === '/api/stories/1/manga-style') body = { manga_style: 'japanese_manga' };
    else if (path.includes('/provider-config')) body = [];
    else if (path.includes('/conversations')) body = [];

    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
}

async function openEditor(page) {
  await page.addInitScript(() => {
    localStorage.setItem('artverse.user', JSON.stringify({ id: 1, username: 'tester', email: 'tester@example.com' }));
  });
  await page.goto('http://127.0.0.1:5173');
  await page.getByRole('button', { name: '故事工作区' }).click();
  await page.getByRole('button', { name: '进入' }).click();
}

let browser;

(async () => {
  browser = await chromium.launch({ channel: 'chrome' });

  const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const emptyPage = await desktop.newPage();
  await mockApi(emptyPage);
  await openEditor(emptyPage);
  await emptyPage.getByRole('button', { name: '分镜生成', exact: true }).waitFor();
  await emptyPage.getByRole('button', { name: '新建设定组', exact: true }).click();
  await emptyPage.getByRole('heading', { name: '设置设定组' }).waitFor();
  await emptyPage.screenshot({ path: '.playwright-mcp/storyboard-controls-empty-desktop.png', fullPage: true });
  await emptyPage.getByRole('button', { name: '添加设定组', exact: true }).click();
  await emptyPage.waitForFunction(() => Array.from(document.querySelectorAll('input')).some((input) => input.value === '新设定组'));
  await emptyPage.getByRole('button', { name: '关闭设定组管理' }).click();
  const assetGroupSelect = emptyPage.locator('select').filter({ has: emptyPage.locator('option', { hasText: '选择设定组' }) });
  await assetGroupSelect.waitFor();
  await emptyPage.screenshot({ path: '.playwright-mcp/storyboard-controls-chapter-desktop.png', fullPage: true });
  if (await emptyPage.getByRole('button', { name: 'AI 重写分镜', exact: true }).count()) {
    throw new Error('Empty storyboard chapter incorrectly shows the rewrite action.');
  }
  await assetGroupSelect.selectOption('7');
  await emptyPage.getByText('该设定组暂无角色卡，请在小说卡片处添加', { exact: true }).waitFor();

  const readyPage = await desktop.newPage();
  await mockApi(readyPage, { existingStoryboard: true });
  await openEditor(readyPage);
  await readyPage.getByRole('button', { name: 'AI 重写分镜', exact: true }).waitFor();
  await readyPage.screenshot({ path: '.playwright-mcp/storyboard-controls-ready-desktop.png', fullPage: true });
  if (await readyPage.getByRole('button', { name: '分镜生成', exact: true }).count()) {
    throw new Error('Existing storyboard chapter incorrectly shows the initial generation action.');
  }

  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mobilePage = await mobile.newPage();
  await mockApi(mobilePage);
  await mobilePage.addInitScript(() => {
    localStorage.setItem('artverse.user', JSON.stringify({ id: 1, username: 'tester', email: 'tester@example.com' }));
  });
  await mobilePage.goto('http://127.0.0.1:5173');
  await mobilePage.getByRole('button', { name: '故事', exact: true }).click();
  await mobilePage.getByRole('button', { name: '进入' }).click();
  await mobilePage.getByRole('button', { name: '漫画分镜', exact: true }).click();
  await mobilePage.getByRole('button', { name: '新建设定组', exact: true }).click();
  await mobilePage.getByRole('heading', { name: '设置设定组' }).waitFor();
  const widths = await mobilePage.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
  }));
  if (widths.content > widths.viewport) throw new Error(`Mobile horizontal overflow: ${JSON.stringify(widths)}`);
  await mobilePage.screenshot({ path: '.playwright-mcp/storyboard-controls-empty-mobile.png' });

  await browser.close();
})().catch((error) => {
  console.error(error);
  void browser?.close();
  process.exitCode = 1;
});
