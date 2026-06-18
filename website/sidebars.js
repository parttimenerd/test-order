// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    {
      type: 'category',
      label: 'Getting started',
      collapsed: false,
      items: ['GETTING_STARTED', 'CHEAT_SHEET', 'DEMOS'],
    },
    {
      type: 'category',
      label: 'Reference',
      items: [
        'CLI_REFERENCE',
        'MAVEN_PLUGIN',
        'MULTI_MODULE_SETUP',
        'CI',
        'KOTEST',
        'FRAMEWORK_COMPARISON',
      ],
    },
    {
      type: 'category',
      label: 'Internals',
      items: [
        'ARCHITECTURE',
        'SCORING',
        'DETECT_DEPENDENCIES',
        'INDEX_FORMAT',
        'RESEARCH',
      ],
    },
    {
      type: 'category',
      label: 'Project',
      items: ['DEVELOPMENT'],
    },
  ],
};

export default sidebars;
