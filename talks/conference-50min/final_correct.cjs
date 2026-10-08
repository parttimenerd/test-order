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

async function getClicksTotal(slideNo) {
  // Navigate to slide, get clicksTotal, return it
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto(`http://localhost:3030/${slideNo}`, { waitUntil: 'networkidle' });
  await sleep(2000);
  const total = await page.evaluate(() => window.__slidev__?.nav?.clicksTotal || 0);
  await browser.close();
  return total;
}

async function screenshotFull(slideNo, outPath) {
  const clicksTotal = await getClicksTotal(slideNo);
  console.log(`Slide ${slideNo} clicksTotal: ${clicksTotal}`);
  
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  // Navigate directly to the fully-revealed state
  const url = clicksTotal > 0 
    ? `http://localhost:3030/${slideNo}?clicks=${clicksTotal}` 
    : `http://localhost:3030/${slideNo}`;
  
  console.log(`Navigating to: ${url}`);
  await page.goto(url, { waitUntil: 'networkidle' });
  await sleep(3000);
  
  const state = await page.evaluate(() => ({
    slideNo: window.__slidev__?.nav?.currentSlideNo,
    clicks: window.__slidev__?.nav?.clicks,
    clicksTotal: window.__slidev__?.nav?.clicksTotal,
    url: window.location.href
  }));
  console.log(`Final state: ${JSON.stringify(state)}`);
  
  await hideRightNav(page);
  await sleep(300);
  await page.screenshot({ path: outPath, fullPage: false });
  console.log(`Saved ${outPath}`);
  await browser.close();
}

(async () => {
  console.log('=== ByteBuddy slide (32) ===');
  await screenshotFull(32, '/tmp/bytebuddy-final.png');
  
  console.log('\n=== Pipeline slide (33) ===');
  await screenshotFull(33, '/tmp/pipeline-final.png');
})();
