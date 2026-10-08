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
    // Also hide the slide overview panel if present
    const panels = document.querySelectorAll('[class*="nav"], [class*="controls"], [class*="panel"]');
    panels.forEach(p => {
      const r = p.getBoundingClientRect();
      if (r.left > window.innerWidth / 2) p.style.display = 'none';
    });
  });
}

async function revealAllClicks(page, slideNo) {
  // The URL format for a specific click state is: /slideNo/clickNo
  // Or we can use keyboard navigation carefully
  // Check current hidden count
  const hidden = await page.evaluate(() => 
    document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
  );
  console.log(`  Initial hidden v-clicks: ${hidden}`);
  
  if (hidden === 0) return;
  
  // Click through using keyboard carefully - track slide number
  for (let i = 0; i < 20; i++) {
    const currentSlide = await page.evaluate(() => window.__slidev__?.nav?.currentSlideNo);
    if (currentSlide !== slideNo) {
      console.log(`  Overshot! On slide ${currentSlide}, going back`);
      await page.keyboard.press('ArrowLeft');
      await sleep(300);
      break;
    }
    const h = await page.evaluate(() => 
      document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
    );
    if (h === 0) {
      console.log(`  All revealed after ${i} clicks`);
      break;
    }
    await page.keyboard.press('ArrowRight');
    await sleep(300);
  }
  await sleep(500);
}

(async () => {
  // === BYTEBUDDY SLIDE ===
  {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.setViewportSize({ width: 1280, height: 720 });
    
    console.log('=== ByteBuddy slide (URL /32) ===');
    await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
    await sleep(3000);
    
    const slideNo = await page.evaluate(() => window.__slidev__?.nav?.currentSlideNo);
    console.log(`Current slide no: ${slideNo}`);
    
    await revealAllClicks(page, slideNo);
    await hideRightNav(page);
    await sleep(300);
    await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
    
    const title = await page.evaluate(() => {
      // Get the visible slide's h1 - should be the first one in the active slide
      const slides = document.querySelectorAll('.slidev-slide');
      for (const s of slides) {
        if (s.style.display !== 'none' && !s.classList.contains('slidev-slide-inactive')) {
          return s.querySelector('h1')?.textContent?.trim() || 'no h1';
        }
      }
      return document.querySelector('h1')?.textContent?.trim();
    });
    console.log(`Captured title: "${title}"`);
    console.log('Saved /tmp/bytebuddy-final.png');
    await browser.close();
  }
  
  // === PIPELINE SLIDE ===
  {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.setViewportSize({ width: 1280, height: 720 });
    
    console.log('\n=== Pipeline slide (URL /33) ===');
    await page.goto('http://localhost:3030/33', { waitUntil: 'networkidle' });
    await sleep(3000);
    
    const slideNo = await page.evaluate(() => window.__slidev__?.nav?.currentSlideNo);
    console.log(`Current slide no: ${slideNo}`);
    
    await revealAllClicks(page, slideNo);
    await hideRightNav(page);
    await sleep(300);
    await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
    
    console.log('Saved /tmp/pipeline-final.png');
    await browser.close();
  }
})();
