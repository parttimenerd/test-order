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

async function screenshotSlide(url, outPath) {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  await page.goto(url, { waitUntil: 'networkidle' });
  await sleep(3000);
  
  // Count hidden elements ONLY in the current active slide
  const info = await page.evaluate(() => {
    const slideNo = window.__slidev__?.nav?.currentSlideNo;
    // Find the active slide container
    const activeSlide = document.querySelector(`.slidev-slide[data-slidev-no="${slideNo}"], .slidev-slide-content`);
    const hiddenInActive = activeSlide ? activeSlide.querySelectorAll('[class*="slidev-vclick-hidden"]').length : 0;
    // Get all h1s in active slide
    const h1s = activeSlide ? Array.from(activeSlide.querySelectorAll('h1')).map(h => h.textContent.trim()) : [];
    return { slideNo, hiddenInActive, h1s };
  });
  console.log(`Slide info: ${JSON.stringify(info)}`);
  
  // Advance through v-clicks in the active slide only
  if (info.hiddenInActive > 0) {
    console.log(`Advancing ${info.hiddenInActive} clicks in active slide...`);
    for (let i = 0; i < info.hiddenInActive + 2; i++) {
      const currentNo = await page.evaluate(() => window.__slidev__?.nav?.currentSlideNo);
      if (currentNo !== info.slideNo) {
        await page.keyboard.press('ArrowLeft');
        await sleep(300);
        break;
      }
      await page.keyboard.press('ArrowRight');
      await sleep(300);
    }
  }
  
  await hideRightNav(page);
  await sleep(300);
  await page.screenshot({ path: outPath, fullPage: false });
  console.log(`Saved ${outPath}`);
  await browser.close();
}

(async () => {
  console.log('=== ByteBuddy slide ===');
  await screenshotSlide('http://localhost:3030/32', '/tmp/bytebuddy-final.png');
  
  console.log('\n=== Pipeline slide ===');
  await screenshotSlide('http://localhost:3030/33', '/tmp/pipeline-final.png');
})();
