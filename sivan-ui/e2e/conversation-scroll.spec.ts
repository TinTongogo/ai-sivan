import {expect, test} from '@playwright/test';

const CONV_ID = '630f91ba-8b29-42ac-98f4-e9321b3d35c3';
const TOKEN =
  'eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiI1NzZkYmI2Zi05MGI2LTRjMDctYmUxNi1kY2UyY2E2ZmQ1NmMiLCJ1c2VybmFtZSI6ImUyZXRlc3RlciIsInJvbGUiOiJ1c2VyIiwiaWF0IjoxNzgwMTQ1ODU2LCJleHAiOjE3ODAyMzIyNTZ9.013vqP17IBcdGaG92K_nSQKWngpLG2MYITu9ZWdDOUgwBQ_tmRFmcCsGk3XWJNFH';
const BASE = 'http://localhost:5173';

function captureMessageRequests(page: any) {
  const requests: string[] = [];
  page.on('request', (req: any) => {
    const url = req.url();
    if (url.includes('/messages') && url.includes(CONV_ID)) {
      requests.push(url.replace(/limit=\d+/, 'limit=N'));
      console.log(`[req] ${url}`);
    }
  });
  return () => requests;
}

async function setupAuth(page: any) {
  // 先访问任意页面一次以设置同源 localStorage
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(
    ({ token }: { token: string }) => {
      localStorage.setItem('token', token);
      localStorage.setItem('accountId', '576dbb6f-90b6-4c07-be16-dce2ca6fd56c');
      localStorage.setItem('username', 'e2etester');
      localStorage.setItem('displayName', 'E2E Tester');
    },
    { token: TOKEN }
  );
}

test.describe('Conversation - 进入对话初始化', () => {
  test('首次进入对话只发 1 次 fetchLatest，不触发 loadMore', async ({ page }) => {
    await setupAuth(page);

    const getRequests = captureMessageRequests(page);

    // 导航到对话页面（带 conversation ID）
    await page.goto(BASE + '/conversations?id=' + CONV_ID, { waitUntil: 'networkidle' });
    // 等待 init 完成（enterConversation → schedule → 4 rAF）
    await page.waitForTimeout(5000);

    // 验证有消息渲染
    const bubbles = page.locator('.bubble-row');
    const count = await bubbles.count();
    console.log(`渲染的消息数: ${count}`);
    expect(count, '应该有消息渲染').toBeGreaterThan(0);

    const reqs = getRequests();
    const loadMoreReqs = reqs.filter((r) => r.includes('before='));
    console.log(`loadMore 请求: ${loadMoreReqs.length}`);
    console.log(`所有 messages 请求: ${JSON.stringify(reqs)}`);

    // 初始进入不应有 loadMore 请求
    expect(loadMoreReqs.length, '初始化时不应触发 loadMore').toBe(0);
  });

  test('对话加载完成后能看到最新消息（在底部）', async ({ page }) => {
    await setupAuth(page);
    await page.goto(BASE + '/conversations?id=' + CONV_ID, { waitUntil: 'networkidle' });
    await page.waitForTimeout(5000);

    // 找到滚动容器，检查 scrollTop 是否在接近底部的位置
    const scroller = page.locator('.dynamic-scroller');
    await expect(scroller).toBeVisible({ timeout: 5000 });

    const scrollInfo = await scroller.evaluate((el: HTMLElement) => ({
      scrollTop: el.scrollTop,
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }));
    console.log('滚动信息:', JSON.stringify(scrollInfo));

    const distFromBottom =
      scrollInfo.scrollHeight - scrollInfo.scrollTop - scrollInfo.clientHeight;
    // 距离底部应该 < 100px（说明滚动到底了）
    expect(distFromBottom, '应定位到最新消息（接近底部）').toBeLessThan(100);
  });
});

test.describe('Conversation - 滚动加载更多', () => {
  test('滚动到顶部触发 loadMore，只发 1 次请求', async ({ page }) => {
    await setupAuth(page);
    await page.goto(BASE + '/conversations?id=' + CONV_ID, { waitUntil: 'networkidle' });
    await page.waitForTimeout(5000);

    const scroller = page.locator('.dynamic-scroller');
    await expect(scroller).toBeVisible({ timeout: 5000 });

    const getRequests = captureMessageRequests(page);

    // 先向下滚动 (复位 _topLoaded)
    await scroller.evaluate((el: HTMLElement) => {
      el.scrollTop = 500;
      el.dispatchEvent(new Event('scroll', { bubbles: true }));
    });
    await page.waitForTimeout(500);

    // 滚动到顶部 (触发 loadMore)
    await scroller.evaluate((el: HTMLElement) => {
      el.scrollTop = 0;
      el.dispatchEvent(new Event('scroll', { bubbles: true }));
    });
    await page.waitForTimeout(4000);

    const reqs = getRequests();
    const loadMoreReqs = reqs.filter((r) => r.includes('before='));
    console.log(`loadMore 请求数: ${loadMoreReqs.length}`);

    // 只应触发 1 次 loadMore
    expect(loadMoreReqs.length, '应该只触发 1 次 loadMore').toBe(1);
  });

  test('连续快速滚动到顶部不会触发重复 loadMore（elastic bounce 场景）', async ({
    page,
  }) => {
    await setupAuth(page);
    await page.goto(BASE + '/conversations?id=' + CONV_ID, { waitUntil: 'networkidle' });
    await page.waitForTimeout(5000);

    const scroller = page.locator('.dynamic-scroller');
    await expect(scroller).toBeVisible({ timeout: 5000 });

    // 先向下滚动 (复位 _topLoaded)
    await scroller.evaluate((el: HTMLElement) => {
      el.scrollTop = 500;
      el.dispatchEvent(new Event('scroll', { bubbles: true }));
    });
    await page.waitForTimeout(500);

    const getRequests = captureMessageRequests(page);

    // 模拟 5 次快速 overscroll bounce
    for (let i = 0; i < 5; i++) {
      await scroller.evaluate((el: HTMLElement) => {
        // 模拟 bounce: 0 → -20 → 0
        el.scrollTop = -20;
        el.dispatchEvent(new Event('scroll', { bubbles: true }));
        el.scrollTop = 0;
        el.dispatchEvent(new Event('scroll', { bubbles: true }));
      });
      await page.waitForTimeout(50);
    }

    await page.waitForTimeout(4000);

    const reqs = getRequests();
    const loadMoreReqs = reqs.filter((r) => r.includes('before='));
    console.log(`bounce 测试 loadMore 请求: ${loadMoreReqs.length}`);

    expect(loadMoreReqs.length, 'elastic bounce 不应触发超过 1 次 loadMore').toBeLessThanOrEqual(1);
  });
});
