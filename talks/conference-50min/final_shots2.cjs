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

async function revealAllClicksOnSlide(page, expectedTitle) {
  // Advance clicks until no more hidden elements, but don't leave the slide
  for (let i = 0; i < 20; i++) {
    const hidden = await page.evaluate(() => 
      document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
    );
    if (hidden === 0) {
      console.log(`  All clicks revealed after ${i} presses`);
      break;
    }
    console.log(`  ${hidden} hidden elements, pressing right...`);
    await page.keyboard.press('ArrowRight');
    await sleep(300);
    
    // Check if we're still on the same slide by checking the title
    const currentTitle = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim());
    if (currentTitle !== expectedTitle) {
      console.log(`  Moved off slide! Going back. Current title: "${currentTitle}"`);
      await page.keyboard.press('ArrowLeft');
      await sleep(400);
      break;
    }
  }
  await sleep(500);
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  // === ByteBuddy slide (32) ===
  console.log('=== Slide 32: Why not ByteBuddy? ===');
  await page.goto('http://localhost:3030/32', { waitUntil: 'networkidle' });
  await sleep(3000);
  
  const title32 = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim());
  console.log(`Title: "${title32}"`);
  
  const initialHidden32 = await page.evaluate(() => 
    document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
  );
  console.log(`Initial hidden: ${initialHidden32}`);
  
  if (initialHidden32 > 0) {
    await revealAllClicksOnSlide(page, title32);
  }
  
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
  console.log('Saved /tmp/bytebuddy-final.png');

  // === Pipeline slide (33) ===
  console.log('\n=== Slide 33: The transformation pipeline ===');
  await page.goto('http://localhost:3030/33', { waitUntil: 'networkidle' });
  await sleep(3000);
  
  const title33 = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim());
  console.log(`Title: "${title33}"`);
  
  const initialHidden33 = await page.evaluate(() => 
    document.querySelectorAll('[class*="slidev-vclick-hidden"]').length
  );
  console.log(`Initial hidden: ${initialHidden33}`);
  
  if (initialHidden33 > 0) {
    await revealAllClicksOnSlide(page, title33);
  }
  
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
  console.log('Saved /tmp/pipeline-final.png');

  await browser.close();
})();
