import React from 'react';
import Link from '@docusaurus/Link';
import styles from '../pages/index.module.css';

const STATS = [
  {
    value: '20+',
    label: 'real bugs caught',
    note: 'across 30+ OSS Java projects',
    href: 'https://github.com/parttimenerd/test-order/tree/main/scripts/bugs',
    hrefLabel: 'See the patches →',
    external: true,
  },
  {
    value: '~95%',
    label: 'detection accuracy',
    note: 'top-3 prioritized tests caught 20 of 21 injected bugs',
    href: 'https://github.com/parttimenerd/test-order/blob/main/scripts/third_party_test_plan.sh',
    hrefLabel: 'Validation harness →',
    external: true,
  },
  {
    value: '92.9%',
    label: 'APFD on first run',
    note: 'sample suite — first failure at position 1 / 7',
    href: '/docs/SCORING',
    hrefLabel: 'How APFD is scored →',
  },
];

const COMPARISON = [
  {
    label: 'Alphabetical / declaration order',
    body:
      'The default. Bug-catching tests run whenever — could be first, could be last. Flaky tests stay anywhere.',
    bad: true,
  },
  {
    label: 'Hand-tuned @Order or test groups',
    body:
      'Works for tiny suites, rots fast. Nobody updates it after the third refactor.',
    bad: true,
  },
  {
    label: 'test-order',
    body:
      'Learns from your CI history. Runs the tests most likely to fail first — automatically, every build.',
    bad: false,
  },
];

export default function HomepageWhy() {
  return (
    <section className={styles.whySection}>
      <h2 className={styles.sectionTitle}>Why test-order?</h2>
      <p className={styles.sectionLede}>
        Most CI time is spent waiting for tests that were never going to fail.
        test-order fixes that without touching your test code.
      </p>

      <div className={styles.statsRow}>
        {STATS.map((s) => (
          <div key={s.label} className={styles.stat}>
            <div className={styles.statValue}>{s.value}</div>
            <div className={styles.statLabel}>{s.label}</div>
            <div className={styles.statNote}>{s.note}</div>
            {s.href ? (
              <Link
                className={styles.statLink}
                to={s.href}
                {...(s.external
                  ? { target: '_blank', rel: 'noopener' }
                  : {})}
              >
                {s.hrefLabel}
              </Link>
            ) : null}
          </div>
        ))}
      </div>

      <p className={styles.statsCaveat}>
        Numbers from a 30-project third-party validation run (June 2026) and a
        sample APFD report. Plus three free-bies: <strong>0</strong> config to add,
        <strong> 100%</strong> of the original coverage preserved, runs on{' '}
        <strong>existing</strong> JUnit / TestNG suites.
      </p>

      <div className={styles.comparison}>
        {COMPARISON.map((c) => (
          <div
            key={c.label}
            className={
              c.bad ? styles.comparisonBad : styles.comparisonGood
            }
          >
            <div className={styles.comparisonMark}>
              {c.bad ? '✗' : '✓'}
            </div>
            <div>
              <div className={styles.comparisonLabel}>{c.label}</div>
              <div className={styles.comparisonBody}>{c.body}</div>
            </div>
          </div>
        ))}
      </div>

      <p className={styles.researchLine}>
        Built on decades of research in test prioritization and impact
        analysis.{' '}
        <Link to="/docs/RESEARCH">See the bibliography →</Link>
      </p>

      <p className={styles.researchLine}>
        An experimental tool by the{' '}
        <Link to="https://sapmachine.io/" target="_blank" rel="noopener">
          SapMachine team
        </Link>{' '}
        — SAP&apos;s OpenJDK distribution. Tested on{' '}
        <Link
          to="https://github.com/parttimenerd/test-order/tree/main/scripts"
          target="_blank"
          rel="noopener"
        >
          30+ open-source Java projects
        </Link>
        .
      </p>
    </section>
  );
}
