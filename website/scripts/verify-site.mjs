#!/usr/bin/env node
// Site verification: checks build artifacts for SEO and content invariants.
// Run after `npm run build`. Exits non-zero on regression.

import { readFileSync, existsSync, statSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const buildDir = resolve(__dirname, '..', 'build');

const failures = [];
const warnings = [];

function check(label, fn) {
  try {
    const result = fn();
    if (result === false) failures.push(label);
    else if (typeof result === 'string') warnings.push(`${label}: ${result}`);
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

function read(rel) {
  return readFileSync(resolve(buildDir, rel), 'utf8');
}

function exists(rel) {
  return existsSync(resolve(buildDir, rel));
}

if (!exists('index.html')) {
  console.error(`Build output not found at ${buildDir}/index.html. Run \`npm run build\` first.`);
  process.exit(2);
}

const home = read('index.html');

// ── SEO essentials ──────────────────────────────────────────────────
check('canonical link present', () => /<link[^>]+rel="canonical"[^>]+href="https:\/\/parttimenerd\.github\.io\/test-order\/"/.test(home));
check('og:title present', () => /<meta[^>]+property="og:title"[^>]+content="[^"]+"/.test(home));
check('og:description present', () => /<meta[^>]+property="og:description"[^>]+content="[^"]+"/.test(home));
check('og:image is PNG', () => {
  const m = home.match(/<meta[^>]+property="og:image"[^>]+content="([^"]+)"/);
  if (!m) return false;
  if (!m[1].endsWith('.png')) return `expected .png, got ${m[1]}`;
  return true;
});
check('og:image:width = 1200', () => /og:image:width"[^>]+content="1200"/.test(home));
check('og:image:height = 630', () => /og:image:height"[^>]+content="630"/.test(home));
check('twitter:card = summary_large_image', () => /twitter:card"[^>]+content="summary_large_image"/.test(home));
check('description meta is non-empty', () => {
  const m = home.match(/<meta[^>]+name="description"[^>]+content="([^"]+)"/);
  return m && m[1].length > 50 && m[1].length < 320;
});

// ── JSON-LD structured data ─────────────────────────────────────────
check('JSON-LD SoftwareApplication block', () => {
  const m = home.match(/<script[^>]*type="application\/ld\+json"[^>]*>(.*?)<\/script>/s);
  if (!m) return false;
  const data = JSON.parse(m[1]);
  if (data['@type'] !== 'SoftwareApplication') return `expected @type=SoftwareApplication, got ${data['@type']}`;
  if (!data.name || !data.url || !data.codeRepository) return 'missing required fields';
  return true;
});

// ── Static assets ───────────────────────────────────────────────────
check('og-image.png exists and is non-trivial', () => {
  if (!exists('img/og-image.png')) return false;
  const size = statSync(resolve(buildDir, 'img/og-image.png')).size;
  if (size < 5000) return `og-image.png suspiciously small: ${size} bytes`;
  return true;
});
check('robots.txt present and lists sitemap', () => {
  if (!exists('robots.txt')) return false;
  const r = read('robots.txt');
  return /Sitemap:\s+https?:\/\/[^\s]+sitemap\.xml/i.test(r);
});
check('sitemap.xml is well-formed XML with urls', () => {
  if (!exists('sitemap.xml')) return false;
  const s = read('sitemap.xml');
  const urls = (s.match(/<loc>/g) || []).length;
  if (urls < 5) return `sitemap has only ${urls} urls; expected 5+`;
  return true;
});
check('sitemap includes homepage', () => {
  const s = read('sitemap.xml');
  return s.includes('https://parttimenerd.github.io/test-order/</loc>');
});

// ── Homepage content invariants ─────────────────────────────────────
check('hero slogan present', () => home.includes('Stop running tests that will never fail'));
check('reassurance line present', () => home.includes('One-block install'));
check('JUnit 6 badge present', () => /JUnit\s*6\s*\/\s*5\s*\/\s*4/.test(home));
check('install section heading present', () => /Add it to your project/i.test(home));
check('affected section heading present', () => /Skip the tests your change/i.test(home));
check('Why section stats present (20+ bugs, 95%, APFD)', () => {
  return /20\+/.test(home) && /95%/.test(home) && /APFD/i.test(home);
});
check('research line links to bibliography', () => /\/docs\/RESEARCH/.test(home));
check('research line text correct', () => /Built on decades of research/.test(home));
check('SapMachine attribution present', () => /SapMachine/.test(home));
check('SapMachine link present', () => /https:\/\/sapmachine\.io\//.test(home));

// ── Output ──────────────────────────────────────────────────────────
const total = failures.length + warnings.length;
if (failures.length === 0 && warnings.length === 0) {
  console.log(`✓ All checks passed.`);
  process.exit(0);
}

if (warnings.length) {
  console.log(`\n⚠ Warnings (${warnings.length}):`);
  warnings.forEach((w) => console.log(`  - ${w}`));
}

if (failures.length) {
  console.log(`\n✗ Failures (${failures.length}):`);
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exit(1);
}
