const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  // Fresh browser instance, no prior state
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
  await sleep(4000);
  
  const info = await page.evaluate(() => ({
    url: window.location.href,
    h1: document.querySelector('h1')?.textContent?.trim(),
    hidden: document.querySelectorAll('[class*="slidev-vclick-hidden"]').length,
    slideNo: window.__slidev__?.nav?.currentSlideNo
  }));
  console.log('Slide 32 info:', JSON.stringify(info, null, 2));
  
  // Screenshot it as-is
  await page.screenshot({ path: '/tmp/check32.png' });
  console.log('Saved /tmp/check32.png');
  
  await browser.close();
})();
