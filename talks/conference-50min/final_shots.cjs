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

async function revealAllClicks(page) {
  // Keep pressing ArrowRight until no more hidden v-click elements on this slide
  // Stop if URL changes (moved to next slide) - then go back
  const startUrl = page.url();
  for (let i = 0; i < 15; i++) {
    const hidden = await page.evaluate(() => 
      document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
    );
    if (hidden === 0) break;
    await page.keyboard.press('ArrowRight');
    await sleep(400);
    const nowUrl = page.url();
    if (nowUrl !== startUrl) {
      // Went to next slide, go back
      await page.keyboard.press('ArrowLeft');
      await sleep(400);
      break;
    }
  }
  await sleep(500);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  // === ByteBuddy slide (32) ===
  console.log('Navigating to slide 32 (Why not ByteBuddy?)...');
  await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
  await sleep(3000);
  
  const title32 = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim());
  console.log(`Slide 32 title: "${title32}"`);
  
  await revealAllClicks(page);
  
  const hiddenAfter = await page.evaluate(() => 
    document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
  );
  console.log(`Hidden v-click elements after reveal: ${hiddenAfter}`);
  
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
  console.log('Saved /tmp/bytebuddy-final.png');

  // === Pipeline slide (33) ===
  console.log('\nNavigating to slide 33 (The transformation pipeline)...');
  await page.goto('http://localhost:3030/33', { waitUntil: 'networkidle' });
  await sleep(3000);
  
  const title33 = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim());
  console.log(`Slide 33 title: "${title33}"`);
  
  await revealAllClicks(page);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
  console.log('Saved /tmp/pipeline-final.png');

  await browser.close();
})();
