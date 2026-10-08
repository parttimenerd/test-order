const { chromium } = require('playwright-chromium');

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function getSlideInfo(page) {
  return await page.evaluate(() => {
    const h1 = document.querySelector('.slidev-layout h1, h1');
    const h2 = document.querySelector('.slidev-layout h2, h2');
    const title = (h1 || h2)?.textContent?.trim() || '';
    // Also check number of v-click elements that are hidden
    const hidden = document.querySelectorAll('[class*="slidev-vclick-hidden"]').length;
    return { title, hidden };
  });
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  // Scan 50-100
  for (let i = 50; i <= 120; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(800);
    const info = await getSlideInfo(page);
    if (info.title) {
      console.log(`URL /${i}: "${info.title}" (hidden vclicks: ${info.hidden})`);
    } else {
      process.stdout.write('.');
    }
    if (i % 10 === 0) process.stdout.write('\n');
  }
  console.log('');
  await browser.close();
})();
