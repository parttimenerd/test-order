const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  
  for (let i = 1; i <= 57; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(600);
    const title = await page.evaluate(() => {
      const h = document.querySelector('h1, h2');
      return h ? h.textContent.trim() : '(no heading)';
    });
    console.log(`${i}: ${title}`);
  }
  
  await browser.close();
})();
