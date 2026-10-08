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

  // We know from scan: slide 51 = "Why not ByteBuddy?", slide 52 = "The transformation pipeline"
  // Verify and screenshot

  await navigateToSlide(page, 51);
  const title51 = await getSlideTitle(page);
  console.log(`Slide 51: "${title51}"`);

  await navigateToSlide(page, 52);
  const title52 = await getSlideTitle(page);
  console.log(`Slide 52: "${title52}"`);

  // Screenshot slide 51 (Why not ByteBuddy?)
  await navigateToSlide(page, 51);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/bytebuddy-final.png', fullPage: false });
  console.log('Saved /tmp/bytebuddy-final.png (slide 51)');

  // Screenshot slide 52 (The transformation pipeline)
  await navigateToSlide(page, 52);
  await hideRightNav(page);
  await page.screenshot({ path: '/tmp/pipeline-final.png', fullPage: false });
  console.log('Saved /tmp/pipeline-final.png (slide 52)');

  await browser.close();
})();
