const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  // Go to slide 32
  await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
  await sleep(2000);
  
  // Check click state
  const state0 = await page.evaluate(() => ({
    url: window.location.href,
    slideNo: window.__slidev__?.nav?.currentSlideNo,
    clicks: window.__slidev__?.nav?.clicks,
    clicksTotal: window.__slidev__?.nav?.clicksTotal,
    clicksStart: window.__slidev__?.nav?.clicksStart,
  }));
  console.log('State at /32:', JSON.stringify(state0));
  
  // Press right once
  await page.keyboard.press('ArrowRight');
  await sleep(500);
  const state1 = await page.evaluate(() => ({
    url: window.location.href,
    slideNo: window.__slidev__?.nav?.currentSlideNo,
    clicks: window.__slidev__?.nav?.clicks,
    clicksTotal: window.__slidev__?.nav?.clicksTotal,
  }));
  console.log('After 1 right:', JSON.stringify(state1));
  
  await page.keyboard.press('ArrowRight');
  await sleep(500);
  const state2 = await page.evaluate(() => ({
    url: window.location.href,
    slideNo: window.__slidev__?.nav?.currentSlideNo,
    clicks: window.__slidev__?.nav?.clicks,
    clicksTotal: window.__slidev__?.nav?.clicksTotal,
  }));
  console.log('After 2 right:', JSON.stringify(state2));
  
  await page.keyboard.press('ArrowRight');
  await sleep(500);
  const state3 = await page.evaluate(() => ({
    url: window.location.href,
    slideNo: window.__slidev__?.nav?.currentSlideNo,
    clicks: window.__slidev__?.nav?.clicks,
    clicksTotal: window.__slidev__?.nav?.clicksTotal,
  }));
  console.log('After 3 right:', JSON.stringify(state3));
  
  await page.keyboard.press('ArrowRight');
  await sleep(500);
  const state4 = await page.evaluate(() => ({
    url: window.location.href,
    slideNo: window.__slidev__?.nav?.currentSlideNo,
    clicks: window.__slidev__?.nav?.clicks,
    clicksTotal: window.__slidev__?.nav?.clicksTotal,
  }));
  console.log('After 4 right:', JSON.stringify(state4));
  
  await browser.close();
})();
