const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  
  for (const n of [30, 31, 32, 33, 34, 35]) {
    await page.goto(`http://localhost:3030/${n}`, { waitUntil: 'networkidle' });
    await sleep(1500);
    const info = await page.evaluate(() => {
      const h1 = document.querySelector('h1');
      const h2 = document.querySelector('h2');
      const url = window.location.href;
      const hidden = document.querySelectorAll('[class*="slidev-vclick-hidden"]').length;
      return { h1: h1?.textContent?.trim(), h2: h2?.textContent?.trim(), url, hidden };
    });
    console.log(`${n}: h1="${info.h1}" h2="${info.h2}" url=${info.url} hidden=${info.hidden}`);
  }
  
  await browser.close();
})();
