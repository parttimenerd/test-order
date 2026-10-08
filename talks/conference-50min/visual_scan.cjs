const { chromium } = require('playwright-chromium');
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1280, height: 720 });
  
  // Scan 27-40 to find ByteBuddy visually by checking for red/green comparison boxes
  for (const n of [27, 28, 29, 30, 31, 32, 33, 34]) {
    await page.goto(`http://localhost:3030/${n}`, { waitUntil: 'networkidle' });
    await sleep(1500);
    
    const info = await page.evaluate(() => {
      // Get all h1/h2 elements including ones inside slidev-layout
      const allH1 = Array.from(document.querySelectorAll('h1')).map(h => h.textContent.trim());
      // Check for ByteBuddy-specific content (red/green boxes)
      const hasBytebuddy = document.body.innerHTML.includes('ByteBuddy');
      const hasPipeline = document.body.innerHTML.includes('ClassFileTransformer') || 
                          document.body.innerHTML.includes('transformation pipeline');
      const hidden = document.querySelectorAll('[class*="slidev-vclick-hidden"]').length;
      // Get the visible text in the main content area
      const layout = document.querySelector('.slidev-layout');
      const layoutText = layout?.textContent?.trim()?.substring(0, 80) || '';
      return { allH1, hasBytebuddy, hasPipeline, hidden, layoutText };
    });
    console.log(`URL ${n}: h1s=[${info.allH1.join(', ')}] bb=${info.hasBytebuddy} pipe=${info.hasPipeline} hidden=${info.hidden}`);
    console.log(`  Layout: "${info.layoutText}"`);
  }
  
  await browser.close();
})();
