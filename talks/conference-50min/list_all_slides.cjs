const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  
  await page.goto('http://localhost:3030/1', { waitUntil: 'networkidle' });
  await sleep(3000);
  
  const slides = await page.evaluate(() => {
    if (window.__slidev__?.nav?.slides) {
      return window.__slidev__.nav.slides.map((s, i) => ({
        no: s.no,
        title: s.title,
        clicks: s.meta?.clicks || 0
      }));
    }
    return [];
  });
  
  slides.forEach(s => console.log(`Slide ${s.no}: "${s.title}" (clicks: ${s.clicks})`));
  console.log(`Total: ${slides.length}`);
  
  await browser.close();
})();
