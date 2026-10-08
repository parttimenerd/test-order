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

async function navigateToSlide(page, slideNum) {
  await page.goto(`http://localhost:3030/${slideNum}`, { waitUntil: 'networkidle' });
  await sleep(3000);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  let byteBuddySlide = null;
  let pipelineSlide = null;

  console.log('Scanning slides 28-50...');
  for (let i = 28; i <= 50; i++) {
    await navigateToSlide(page, i);
    const title = await getSlideTitle(page);
    console.log(`Slide ${i}: "${title}"`);
    
    if (title && (title.toLowerCase().includes('bytebuddy') || title.toLowerCase().includes('byte buddy'))) {
      console.log(`  -> Found ByteBuddy slide at ${i}`);
      byteBuddySlide = i;
    }
    if (title && title.toLowerCase().includes('transformation pipeline')) {
      console.log(`  -> Found Pipeline slide at ${i}`);
      pipelineSlide = i;
    }
    if (byteBuddySlide && pipelineSlide) break;
  }

  if (!byteBuddySlide) {
    console.log('Not found in 28-50, scanning 1-27...');
    for (let i = 1; i <= 27; i++) {
      await navigateToSlide(page, i);
      const title = await getSlideTitle(page);
      console.log(`Slide ${i}: "${title}"`);
      if (title && (title.toLowerCase().includes('bytebuddy') || title.toLowerCase().includes('byte buddy'))) {
        console.log(`  -> Found ByteBuddy slide at ${i}: "${title}"`);
        byteBuddySlide = i;
        break;
      }
    }
  }

  if (byteBuddySlide && !pipelineSlide) {
    for (let i = byteBuddySlide + 1; i <= byteBuddySlide + 5; i++) {
      await navigateToSlide(page, i);
      const title = await getSlideTitle(page);
      console.log(`Slide ${i} (after ByteBuddy): "${title}"`);
      if (title && title.toLowerCase().includes('pipeline')) {
        pipelineSlide = i;
        break;
      }
    }
    if (!pipelineSlide) {
      pipelineSlide = byteBuddySlide + 1;
      console.log(`Using slide ${pipelineSlide} as pipeline slide (immediately after ByteBuddy)`);
    }
  }

  if (byteBuddySlide) {
    await navigateToSlide(page, byteBuddySlide);
    await hideRightNav(page);
    await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
    console.log(`Saved /tmp/bytebuddy-final.png (slide ${byteBuddySlide})`);
  } else {
    console.log('ERROR: ByteBuddy slide not found!');
  }

  if (pipelineSlide) {
    await navigateToSlide(page, pipelineSlide);
    await hideRightNav(page);
    await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
    console.log(`Saved /tmp/pipeline-final.png (slide ${pipelineSlide})`);
  } else {
    console.log('ERROR: Pipeline slide not found!');
  }

  await browser.close();
})();
