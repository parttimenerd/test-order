const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  for (let i = 28; i <= 36; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(1500);
    
    const info = await page.evaluate(() => {
      // Check all classes on slide elements
      const slides = document.querySelectorAll('.slidev-slide');
      const slideInfo = Array.from(slides).slice(0, 5).map(s => ({
        classes: s.className,
        h1: s.querySelector('h1')?.textContent?.trim()?.substring(0, 40) || ''
      }));
      
      // Find the current slide by checking data attribute
      const current = document.querySelector('[class*="slidev-slide"][data-slidev-current]') ||
                      document.querySelector('[class*="current"]');
      
      return {
        slideNo: window.__slidev__?.nav?.currentSlideNo,
        clicksTotal: window.__slidev__?.nav?.clicksTotal,
        slides: slideInfo.map(s => `${s.classes.split(' ').filter(c => c.includes('slidev') || c.includes('current') || c.includes('active')).join(',')} -> "${s.h1}"`).join('\n    ')
      };
    });
    console.log(`\nURL ${i}: slideNo=${info.slideNo} clicksTotal=${info.clicksTotal}`);
    console.log(`  ${info.slides}`);
  }
  
  await browser.close();
})();
