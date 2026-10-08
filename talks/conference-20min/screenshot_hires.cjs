const { chromium } = require('playwright-chromium');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1400, height: 900 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  await page.goto('http://localhost:9090/');
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'talks/conference-20min/public/images/dashboard-tests.png' });

  await page.evaluate(() => document.querySelectorAll('nav button')[1].click());
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'talks/conference-20min/public/images/dashboard-analytics.png' });

  await page.evaluate(() => document.querySelectorAll('nav button')[2].click());
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'talks/conference-20min/public/images/dashboard-weights.png' });

  await page.evaluate(() => {
    const link = Array.from(document.querySelectorAll('*')).find(el => el.textContent.trim() === 'coverage ✓');
    if (link) link.click();
  });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'talks/conference-20min/public/images/dashboard-coverage.png' });

  await browser.close();
  console.log('done');
})();
