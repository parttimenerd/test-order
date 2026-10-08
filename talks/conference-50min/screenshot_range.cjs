const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function hideRightNav(page) {
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('*')) {
      const s = window.getComputedStyle(el);
      if (s.position === 'fixed') {
        const r = el.getBoundingClientRect();
        if (r.left > window.innerWidth / 2) el.style.display = 'none';
      }
    }
  });
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  for (let i = 29; i <= 35; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(2000);
    await hideRightNav(page);
    await page.screenshot({ path: `/tmp/slide_${i}.png` });
    console.log(`Saved /tmp/slide_${i}.png`);
  }
  
  await browser.close();
})();
