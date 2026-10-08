const { chromium } = require('playwright-chromium');

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function hideRightNav(page) {
  await page.evaluate(() => {
    const all = document.querySelectorAll('*');
    for (const el of all) {
      const style = window.getComputedStyle(el);
      if (style.position === 'fixed') {
        const rect = el.getBoundingClientRect();
        if (rect.left > window.innerWidth / 2) {
          el.style.display = 'none';
        }
      }
    }
  });
}

async function getSlideTitle(page) {
  return await page.evaluate(() => {
    const selectors = ['.slidev-layout h1', '.slidev-layout h2', 'h1', 'h2'];
    for (const sel of selectors) {
      const el = document.querySelector(sel);
      if (el) return el.textContent.trim();
    }
    return '';
  });
}

// Navigate to a slide URL and advance through all v-click steps
async function navigateToSlideFullyRevealed(page, slideNum) {
  // Go to the slide
  await page.goto(`http://localhost:3030/${slideNum}`, { waitUntil: 'networkidle' });
  await sleep(2000);
  
  // Press right arrow multiple times to advance through all v-click steps
  // until we detect we've moved to the next slide or clicks are exhausted
  for (let i = 0; i < 10; i++) {
    const titleBefore = await getSlideTitle(page);
    const urlBefore = page.url();
    
    // Press right arrow to advance
    await page.keyboard.press('ArrowRight');
    await sleep(500);
    
    const urlAfter = page.url();
    const titleAfter = await getSlideTitle(page);
    
    // If the URL changed (moved to next slide), go back
    if (urlAfter !== urlBefore || titleAfter !== titleBefore) {
      console.log(`  Detected slide advance at click ${i+1}, going back`);
      await page.keyboard.press('ArrowLeft');
      await sleep(500);
      break;
    }
  }
  await sleep(1000);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  // Scan for "Why not ByteBuddy?" - we know it's around 51 from the title scan
  // but Slidev URL numbers differ from separator count due to v-click steps
  // Let's scan more carefully using URL patterns
  
  let byteBuddySlide = null;
  let pipelineSlide = null;
  
  console.log('Scanning for correct URL numbers...');
  for (let i = 45; i <= 70; i++) {
    await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
    await sleep(1500);
    const title = await getSlideTitle(page);
    console.log(`URL /${i}: "${title}"`);
    
    if (title === 'Why not ByteBuddy?') {
      byteBuddySlide = i;
      console.log(`  -> ByteBuddy at URL /${i}`);
    }
    if (title === 'The transformation pipeline') {
      pipelineSlide = i;
      console.log(`  -> Pipeline at URL /${i}`);
    }
    if (byteBuddySlide && pipelineSlide) break;
  }

  console.log(`\nResults: ByteBuddy=${byteBuddySlide}, Pipeline=${pipelineSlide}`);

  // Screenshot ByteBuddy fully revealed
  if (byteBuddySlide) {
    console.log(`\nScreenshotting ByteBuddy slide (URL /${byteBuddySlide})...`);
    await navigateToSlideFullyRevealed(page, byteBuddySlide);
    await hideRightNav(page);
    await sleep(500);
    await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
    console.log('Saved /tmp/bytebuddy-final.png');
  }

  // Screenshot Pipeline
  if (pipelineSlide) {
    console.log(`\nScreenshotting Pipeline slide (URL /${pipelineSlide})...`);
    await page.goto(`http://localhost:3030/${pipelineSlide}`, { waitUntil: 'networkidle' });
    await sleep(3000);
    await hideRightNav(page);
    await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
    console.log('Saved /tmp/pipeline-final.png');
  }

  await browser.close();
})();
