#!/usr/bin/env node
/**
 * check-asciinema-players.mjs
 *
 * Visits every page that embeds an AsciinemaPlayer and verifies that each
 * player slot either:
 *   (a) loaded a cast (local or GitHub Pages fallback) → .ap-player is visible
 *   (b) cast is not available anywhere → slot is empty (warn, not fail)
 *
 * Exit 0 — all reachable casts rendered their player element.
 * Exit 1 — a cast that IS reachable (local or remote) failed to render.
 *
 * Usage:
 *   BASE_URL=http://localhost:3737/test-order node scripts/check-asciinema-players.mjs
 */

import { chromium } from 'playwright';

const BASE         = (process.env.BASE_URL ?? 'http://localhost:3737/test-order').replace(/\/$/, '');
const REMOTE_BASE  = 'https://parttimenerd.github.io/test-order';

const PAGES = [
  {
    url: '/',
    players: [
      { src: '/casts/demo-hello-world.cast', label: 'hero cast' },
    ],
  },
  {
    url: '/docs/DEMOS',
    players: [
      { src: '/casts/demo-hello-world.cast',  label: 'hello-world' },
      { src: '/casts/demo-learn.cast',         label: 'learn' },
      { src: '/casts/demo-tiered.cast',        label: 'tiered' },
      { src: '/casts/demo-dashboard.cast',     label: 'dashboard' },
      { src: '/casts/demo-optimizer.cast',     label: 'optimizer' },
      { src: '/casts/demo-diagnose.cast',      label: 'diagnose' },
      { src: '/casts/demo-multi-module.cast',  label: 'multi-module' },
      { src: '/casts/demo-spring-ai.cast',     label: 'spring-ai' },
      { src: '/casts/demo-spring-boot.cast',   label: 'spring-boot' },
      { src: '/casts/demo-sap-cds4j.cast',     label: 'sap-cds4j' },
    ],
  },
  {
    url: '/docs/GETTING_STARTED',
    players: [
      { src: '/casts/demo-hello-world.cast', label: 'hello-world' },
      { src: '/casts/demo-learn.cast',        label: 'learn' },
      { src: '/casts/demo-dashboard.cast',    label: 'dashboard' },
      { src: '/casts/demo-diagnose.cast',     label: 'diagnose' },
    ],
  },
  {
    url: '/docs/CLI_REFERENCE',
    players: [
      { src: '/casts/demo-tiered.cast',   label: 'tiered' },
      { src: '/casts/demo-diagnose.cast', label: 'diagnose' },
    ],
  },
  {
    url: '/docs/FLAKY_AND_CACHING',
    players: [
      { src: '/casts/demo-dashboard.cast', label: 'dashboard' },
    ],
  },
  {
    url: '/docs/MULTI_MODULE_SETUP',
    players: [
      { src: '/casts/demo-multi-module.cast', label: 'multi-module' },
    ],
  },
];

async function headOk(page, url) {
  try {
    const status = await page.evaluate(
      async (u) => { try { return (await fetch(u, { method: 'HEAD' })).status; } catch { return 0; } },
      url,
    );
    return status >= 200 && status < 300;
  } catch { return false; }
}

async function checkPage(browser, { url, players }) {
  const page = await browser.newPage();
  page.on('pageerror', err => console.warn(`  [pageerror] ${err.message}`));
  const results = [];

  try {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded', timeout: 15_000 });
    // Wait for BrowserOnly hydration + async HEAD fetches + player mount.
    await page.waitForTimeout(4000);

    // Collect all player slots in DOM order (ap-wrapper = loaded, empty div = nothing rendered yet).
    const slots = await page.evaluate(() => {
      return [...document.querySelectorAll('*')]
        .filter(el => el.classList?.contains('ap-wrapper'))
        .map(el => ({ type: 'player', visible: el.offsetParent !== null || el.getBoundingClientRect().width > 0 }));
    });

    for (let i = 0; i < players.length; i++) {
      const { src, label } = players[i];
      const localOk  = await headOk(page, BASE + src);
      const remoteOk = localOk ? true : await headOk(page, REMOTE_BASE + src);
      const available = localOk || remoteOk;
      const origin    = localOk ? 'local' : remoteOk ? 'GitHub Pages' : 'nowhere';

      const slot = slots[i];
      const rendered = slot?.type === 'player' && slot?.visible;

      if (!available) {
        results.push({ label, src, available: false, rendered, pass: true, warn: true,
          note: 'not available locally or on GitHub Pages — skipping' });
      } else if (rendered) {
        results.push({ label, src, available: true, rendered, pass: true, warn: false,
          note: `player rendered ✓  (source: ${origin})` });
      } else {
        results.push({ label, src, available: true, rendered, pass: false, warn: false,
          note: `cast available (${origin}) but .ap-player not found at slot ${i}` });
      }
    }
  } finally {
    await page.close();
  }
  return results;
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const all = [];

  for (const spec of PAGES) {
    console.log(`\n── ${BASE}${spec.url} ──`);
    for (const r of await checkPage(browser, spec)) {
      const icon = r.pass ? (r.warn ? '⚠' : '✓') : '✗';
      console.log(`  ${icon}  ${r.label.padEnd(14)} — ${r.note}`);
      all.push(r);
    }
  }

  await browser.close();

  const ok      = all.filter(r =>  r.pass && !r.warn);
  const skipped = all.filter(r =>  r.warn);
  const broken  = all.filter(r => !r.pass);

  console.log('\n────────────────────────────────────────');
  console.log(`  ✓ rendered:  ${ok.length}`);
  if (skipped.length) console.log(`  ⚠ unavailable (neither local nor remote): ${skipped.length}`);
  if (broken.length)  console.log(`  ✗ BROKEN (cast reachable but player didn't mount): ${broken.length}`);
  console.log('────────────────────────────────────────');

  if (broken.length) {
    console.error(`\nFAIL — ${broken.length} player(s) broken.`);
    process.exit(1);
  }
  console.log('\nAll reachable asciinema players rendered successfully.');
}

main().catch(err => { console.error(err); process.exit(1); });
