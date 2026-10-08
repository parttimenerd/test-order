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
  // ByteBuddy slide: URL /31, fully revealed = clicks=3
  {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.setViewportSize({ width: 1280, height: 720 });
    
    console.log('=== ByteBuddy (URL /31, clicks=3) ===');
    await page.goto('http://localhost:3030/31?clicks=3', { waitUntil: 'networkidle' });
    await sleep(3000);
    
    const state = await page.evaluate(() => ({
      slideNo: window.__slidev__?.nav?.currentSlideNo,
      clicks: window.__slidev__?.nav?.clicks,
      clicksTotal: window.__slidev__?.nav?.clicksTotal,
    }));
    console.log('State:', JSON.stringify(state));
    
    await hideRightNav(page);
    await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
    console.log('Saved /tmp/bytebuddy-final.png');
    await browser.close();
  }
  
  // Pipeline slide: URL /32, fully revealed (clicksTotal unknown, check first)
  {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.setViewportSize({ width: 1280, height: 720 });
    
    // Check clicksTotal first
    await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
    await sleep(2000);
    const clicksTotal = await page.evaluate(() => window.__slidev__?.nav?.clicksTotal || 0);
    console.log(`\n=== Pipeline (URL /32, clicksTotal=${clicksTotal}) ===`);
    
    const url = clicksTotal > 0 ? `http://localhost:3030/32?clicks=${clicksTotal}` : 'http://localhost:3030/32';
    await page.goto(url, { waitUntil: 'networkidle' });
    await sleep(3000);
    
    const state = await page.evaluate(() => ({
      slideNo: window.__slidev__?.nav?.currentSlideNo,
      clicks: window.__slidev__?.nav?.clicks,
      clicksTotal: window.__slidev__?.nav?.clicksTotal,
    }));
    console.log('State:', JSON.stringify(state));
    
    await hideRightNav(page);
    await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
    console.log('Saved /tmp/pipeline-final.png');
    await browser.close();
  }
})();
