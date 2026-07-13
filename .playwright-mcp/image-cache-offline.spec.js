const { chromium } = require('D:/develop/NodeJS/node_cache/_npx/e41f203b7505f1fb/node_modules/playwright');

const frontendUrl = process.env.ARTVERSE_FRONTEND_URL || 'http://127.0.0.1:5173';
const imagePath = 'image_gen/1/offline-cache-test.png';
const record = {
  id: 991001,
  prompt: '离线缓存测试',
  image_url: imagePath,
  model: 'gpt-image-2',
  size: '1024x1024',
  status: 'SUCCEEDED',
  failure_reason: null,
  created_at: '2026-07-13T12:00:00+08:00',
  completed_at: '2026-07-13T12:00:01+08:00',
};
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
);

(async () => {
  const browser = await chromium.launch({ channel: 'chrome' });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  let page = await context.newPage();
  let backendOnline = true;

  await context.addInitScript(() => {
    localStorage.setItem('artverse.user', JSON.stringify({ id: 1, username: 'cache-test', email: 'cache@example.com' }));
  });
  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (!url.pathname.startsWith('/api/') && !url.pathname.startsWith('/static/')) {
      await route.continue();
      return;
    }
    if (!backendOnline) {
      await route.abort('connectionrefused');
      return;
    }
    if (url.pathname === '/api/image-gen/history') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ content: [record], total_pages: 1, total_elements: 1 }),
      });
      return;
    }
    if (url.pathname === `/static/manga/${imagePath}`) {
      await route.fulfill({ status: 200, contentType: 'image/png', body: png });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });

  await page.goto(frontendUrl);
  await page.getByRole('button', { name: 'AI 生图' }).click();
  const onlineImage = page.locator('img[alt="离线缓存测试"]');
  await onlineImage.waitFor();
  await page.waitForFunction(() => {
    const image = document.querySelector('img[alt="离线缓存测试"]');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0;
  });
  const cached = await page.evaluate((key) => new Promise((resolve, reject) => {
    const request = indexedDB.open('artverse-image-cache', 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const getRequest = request.result.transaction('images').objectStore('images').get(key);
      getRequest.onerror = () => reject(getRequest.error);
      getRequest.onsuccess = () => resolve(Boolean(getRequest.result?.blob));
    };
  }), `generated:${imagePath}`);
  if (!cached) throw new Error('Generated image was not written to IndexedDB');
  await page.evaluate(({ referenceKey, pngBytes }) => new Promise((resolve, reject) => {
    const themes = JSON.parse(localStorage.getItem('artverse.genThemes') || '[]');
    themes[0].messages.push({
      id: 'offline-reference-message',
      type: 'user',
      prompt: '参考图缓存测试',
      refImageKeys: [referenceKey],
    });
    localStorage.setItem('artverse.genThemes', JSON.stringify(themes));

    const request = indexedDB.open('artverse-image-cache', 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const transaction = request.result.transaction('images', 'readwrite');
      transaction.objectStore('images').put({
        key: referenceKey,
        blob: new Blob([Uint8Array.from(pngBytes)], { type: 'image/png' }),
        updatedAt: Date.now(),
      });
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    };
  }), { referenceKey: 'reference:offline-reference-message:0', pngBytes: [...png] });

  backendOnline = false;
  await page.close();
  page = await context.newPage();
  await page.goto(frontendUrl);
  await page.getByRole('button', { name: 'AI 生图' }).click();
  const offlineImage = page.locator('img[alt="离线缓存测试"]');
  await offlineImage.waitFor();
  const offlineState = await offlineImage.evaluate((image) => ({
    src: image.getAttribute('src'),
    complete: image.complete,
    naturalWidth: image.naturalWidth,
  }));
  if (!offlineState.src?.startsWith('blob:') || !offlineState.complete || offlineState.naturalWidth === 0) {
    throw new Error(`Offline image did not load from IndexedDB: ${JSON.stringify(offlineState)}`);
  }
  const offlineReference = page.locator('img[alt="参考图 1"]');
  await offlineReference.waitFor();
  const referenceState = await offlineReference.evaluate((image) => ({
    src: image.getAttribute('src'),
    complete: image.complete,
    naturalWidth: image.naturalWidth,
  }));
  if (!referenceState.src?.startsWith('blob:') || !referenceState.complete || referenceState.naturalWidth === 0) {
    throw new Error(`Offline reference did not load from IndexedDB: ${JSON.stringify(referenceState)}`);
  }

  console.log('IMAGE_CACHE_OFFLINE_OK', JSON.stringify({ generated: offlineState, reference: referenceState }));
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
