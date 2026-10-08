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
  
  // Try slide 32 at click 0
  await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
  await sleep(3000);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/slide32_clicks0.png' });
  console.log('Saved slide32_clicks0.png');
  
  // Try slide 32 at clicks=1
  await page.goto('http://localhost:3030/32?clicks=1', { waitUntil: 'networkidle' });
  await sleep(2000);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/slide32_clicks1.png' });
  console.log('Saved slide32_clicks1.png');
  
  // Try slide 32 at clicks=2
  await page.goto('http://localhost:3030/32?clicks=2', { waitUntil: 'networkidle' });
  await sleep(2000);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/slide32_clicks2.png' });
  console.log('Saved slide32_clicks2.png');
  
  // Try slide 32 at clicks=3
  await page.goto('http://localhost:3030/32?clicks=3', { waitUntil: 'networkidle' });
  await sleep(2000);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/slide32_clicks3.png' });
  console.log('Saved slide32_clicks3.png');
  
  await browser.close();
})();
