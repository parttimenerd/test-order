const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  for (let i = 25; i <= 40; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(1000);
    
    const info = await page.evaluate(() => {
      // Get the slide that's actually in the viewport (not off-screen)
      // Slidev positions the active slide at 0,0 and neighbors offset
      const slides = document.querySelectorAll('.slidev-slide');
      let activeTitle = '';
      for (const s of slides) {
        const rect = s.getBoundingClientRect();
        // Active slide should be roughly at x=0, y=0
        if (rect.left > -100 && rect.left < 100 && rect.top > -100 && rect.top < 100) {
          const h1 = s.querySelector('h1');
          activeTitle = h1?.textContent?.trim() || '';
          break;
        }
      }
      return {
        slideNo: window.__slidev__?.nav?.currentSlideNo,
        clicksTotal: window.__slidev__?.nav?.clicksTotal,
        activeTitle,
        url: window.location.href
      };
    });
    console.log(`URL ${i}: slide=${info.slideNo} clicks=${info.clicksTotal} title="${info.activeTitle}"`);
  }
  
  await browser.close();
})();
