const { chromium } = require('D:/develop/NodeJS/node_cache/_npx/e41f203b7505f1fb/node_modules/playwright');

const stories = [
  { id: 1, title: '雾港来信', description: '一名修复师在旧照片中发现失踪多年的城市。', cover_image: null, created_at: '2026-07-02T10:00:00Z' },
  { id: 2, title: '最后一班夜车', description: '午夜列车只搭载仍有遗憾的人。', cover_image: null, created_at: '2026-06-18T10:00:00Z' },
  { id: 3, title: '纸上星河', description: '少年画出的星图开始改变真实夜空。', cover_image: null, created_at: '2026-05-21T10:00:00Z' },
];

async function mockApi(page) {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    let body = [];
    if (url.pathname === '/api/stories') body = stories;
    else if (url.pathname.includes('/provider-config')) body = [];
    else if (url.pathname.includes('/conversations')) body = [];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome' });
  const authenticated = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const storyPage = await authenticated.newPage();
  await storyPage.addInitScript(() => localStorage.setItem('artverse.user', JSON.stringify({ id: 1, username: 'designer', email: 'designer@example.com' })));
  await mockApi(storyPage);
  await storyPage.goto('http://127.0.0.1:5173');
  await storyPage.getByRole('button', { name: '故事工作区' }).click();
  await storyPage.getByText('你的故事').waitFor();
  await storyPage.screenshot({ path: '.playwright-mcp/artverse-redesign-stories.png', fullPage: true });

  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mobileStoryPage = await mobile.newPage();
  await mobileStoryPage.addInitScript(() => localStorage.setItem('artverse.user', JSON.stringify({ id: 1, username: 'designer', email: 'designer@example.com' })));
  await mockApi(mobileStoryPage);
  await mobileStoryPage.goto('http://127.0.0.1:5173');
  await mobileStoryPage.getByRole('button', { name: '故事', exact: true }).click();
  await mobileStoryPage.getByText('你的故事').waitFor();
  const mobileWidths = await mobileStoryPage.evaluate(() => ({ viewport: document.documentElement.clientWidth, content: document.documentElement.scrollWidth }));
  if (mobileWidths.content > mobileWidths.viewport) throw new Error(`Mobile horizontal overflow: ${JSON.stringify(mobileWidths)}`);
  await mobileStoryPage.screenshot({ path: '.playwright-mcp/artverse-redesign-stories-mobile.png' });

  const anonymous = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const loginPage = await anonymous.newPage();
  await mockApi(loginPage);
  await loginPage.goto('http://127.0.0.1:5173');
  await loginPage.getByRole('button', { name: '登录' }).click();
  await loginPage.getByRole('button', { name: '注册' }).waitFor();
  await loginPage.screenshot({ path: '.playwright-mcp/artverse-redesign-login.png' });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
