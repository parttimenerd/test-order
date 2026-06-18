// @ts-check
import { themes as prismThemes } from 'prism-react-renderer';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

const SLOGAN = 'Stop running tests that will never fail.';
const TAGLINE =
  'test-order learns which of your tests actually catch bugs — and runs those first.';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'test-order',
  tagline: SLOGAN,
  favicon: 'img/favicon.ico',

  url: 'https://parttimenerd.github.io',
  baseUrl: '/test-order/',

  organizationName: 'parttimenerd',
  projectName: 'test-order',
  trailingSlash: false,

  onBrokenLinks: 'warn',
  onBrokenAnchors: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    mermaid: true,
    format: 'detect',
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  stylesheets: [
    {
      href: 'https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css',
      type: 'text/css',
      crossorigin: 'anonymous',
    },
  ],

  themes: [
    '@docusaurus/theme-mermaid',
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        indexDocs: true,
        indexPages: true,
        docsRouteBasePath: '/docs',
        searchBarPosition: 'right',
      },
    ],
  ],

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          path: '../docs',
          routeBasePath: '/docs',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/parttimenerd/test-order/edit/main/docs/',
          exclude: ['**/*.pdf', '**/*.html', '**/ci-examples/**'],
          remarkPlugins: [remarkMath],
          rehypePlugins: [rehypeKatex],
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
        sitemap: {
          changefreq: 'weekly',
          priority: 0.5,
          ignorePatterns: [],
          filename: 'sitemap.xml',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/og-image.png',
      colorMode: {
        defaultMode: 'light',
        respectPrefersColorScheme: true,
      },
      metadata: [
        { name: 'description', content: TAGLINE },
        { name: 'keywords', content: 'test-order, JUnit, Maven, Gradle, test prioritization, CI optimization, flaky tests, fail-fast, TIA, test impact analysis' },
        { property: 'og:title', content: 'test-order — ' + SLOGAN },
        { property: 'og:description', content: TAGLINE },
        { property: 'og:type', content: 'website' },
        { property: 'og:image:width', content: '1200' },
        { property: 'og:image:height', content: '630' },
        { property: 'og:image:type', content: 'image/png' },
        { property: 'og:image:alt', content: 'test-order — ' + SLOGAN },
        { name: 'twitter:card', content: 'summary_large_image' },
        { name: 'twitter:title', content: 'test-order — ' + SLOGAN },
        { name: 'twitter:description', content: TAGLINE },
        { name: 'twitter:image:alt', content: 'test-order — ' + SLOGAN },
      ],
      navbar: {
        title: 'test-order',
        logo: {
          alt: 'test-order',
          src: 'img/logo.svg',
        },
        items: [
          { to: '/docs/GETTING_STARTED', label: 'Docs', position: 'left' },
          { to: '/docs/DEMOS', label: 'Demos', position: 'left' },
          { to: '/docs/CLI_REFERENCE', label: 'CLI', position: 'left' },
          {
            href: 'https://github.com/parttimenerd/test-order',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        copyright:
          'An experimental tool by the <a href="https://sapmachine.io/" target="_blank" rel="noopener">SapMachine team</a>. ' +
          SLOGAN +
          ' — MIT licensed. Built with Docusaurus.',
        links: [
          {
            title: 'Docs',
            items: [
              { label: 'Getting started', to: '/docs/GETTING_STARTED' },
              { label: 'Cheat sheet', to: '/docs/CHEAT_SHEET' },
              { label: 'CLI reference', to: '/docs/CLI_REFERENCE' },
              { label: 'Maven plugin', to: '/docs/MAVEN_PLUGIN' },
            ],
          },
          {
            title: 'Internals',
            items: [
              { label: 'Architecture', to: '/docs/ARCHITECTURE' },
              { label: 'Scoring (APFD)', to: '/docs/SCORING' },
              { label: 'Affected detection', to: '/docs/DETECT_DEPENDENCIES' },
              { label: 'Research', to: '/docs/RESEARCH' },
            ],
          },
          {
            title: 'Project',
            items: [
              { label: 'GitHub', href: 'https://github.com/parttimenerd/test-order' },
              {
                label: 'Report an issue',
                href: 'https://github.com/parttimenerd/test-order/issues/new',
              },
              { label: 'Demos', to: '/docs/DEMOS' },
            ],
          },
          {
            title: 'About',
            items: [
              {
                label: 'SapMachine',
                href: 'https://sapmachine.io/',
              },
              {
                label: 'SapMachine on GitHub',
                href: 'https://github.com/SAP/SapMachine',
              },
              {
                label: 'Author — @parttimenerd',
                href: 'https://github.com/parttimenerd',
              },
            ],
          },
        ],
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'groovy', 'kotlin', 'bash', 'yaml'],
      },
    }),
};

export default config;
