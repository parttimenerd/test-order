const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  
  // Go to slide 1 and check total count from Slidev UI
  await page.goto('http://localhost:3030/1', { waitUntil: 'networkidle' });
  await sleep(2000);
  
  // Try to get total slide count from Slidev's state
  const info = await page.evaluate(() => {
    // Look for the slide counter in the UI
    const counter = document.querySelector('.slidev-page-number, [class*="page"], [class*="counter"]');
    // Also try to get from Vite's injected context
    const nav = document.querySelector('nav');
    return {
      counter: counter?.textContent,
      nav: nav?.textContent?.trim()?.substring(0, 100),
      // Check Slidev's global state
      slidevData: window.__slidev__ ? JSON.stringify(Object.keys(window.__slidev__)) : 'not found'
    };
  });
  console.log('Slidev info:', JSON.stringify(info, null, 2));
  
  // Also check max reachable slide
  let lastValid = 1;
  for (let i = 1; i <= 120; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(300);
    const hasContent = await page.evaluate(() => {
      const el = document.querySelector('.slidev-layout, .slidev-slide');
      return !!el;
    });
    if (hasContent) lastValid = i;
    else if (i > lastValid + 3) break;
  }
  console.log(`Last valid slide: ${lastValid}`);
  
  await browser.close();
})();
