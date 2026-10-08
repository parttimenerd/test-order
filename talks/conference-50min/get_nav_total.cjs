const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  
  await page.goto('http://localhost:3030/1', { waitUntil: 'networkidle' });
  await sleep(3000);
  
  // Get Slidev nav state which has total slide count
  const navState = await page.evaluate(() => {
    // Access Slidev's internal state
    if (window.__slidev__?.nav) {
      const nav = window.__slidev__.nav;
      return {
        currentSlide: nav.currentSlide,
        total: nav.total,
        totalPages: nav.totalPages,
        clicks: nav.clicks,
        clicksTotal: nav.clicksTotal,
        allKeys: Object.keys(nav).filter(k => typeof nav[k] !== 'function').slice(0, 30)
      };
    }
    return { error: 'no nav' };
  });
  console.log('Nav state:', JSON.stringify(navState, null, 2));
  
  await browser.close();
})();
