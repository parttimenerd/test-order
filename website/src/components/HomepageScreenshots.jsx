import React from 'react';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from '../pages/index.module.css';

/**
 * Prefers freshly-captured screenshots in docs/assets/screenshots/.
 * Falls back to the older committed PNGs at docs/dashboard-*.png if the
 * autocapture job hasn't run yet — the markdown references those names too,
 * so they always exist in git.
 */
const SHOTS = [
  {
    src: '/img/screenshots/dashboard-overview.png',
    fallback: '/img/screenshots/dashboard-main.png',
    caption: 'Overview — KPI row, history, top tests at a glance.',
  },
  {
    src: '/img/screenshots/dashboard-tests.png',
    fallback: '/img/screenshots/dashboard-tests-tab.png',
    caption: 'Tests — drill into class detail, scores, and history.',
  },
  {
    src: '/img/screenshots/dashboard-analytics.png',
    fallback: '/img/screenshots/dashboard-analytics-tab.png',
    caption: 'Analytics — flakiness, coverage, weight curves.',
  },
];

export default function HomepageScreenshots() {
  return (
    <section className={`${styles.section} ${styles.sectionAlt}`}>
      <h2 className={styles.sectionTitle}>See your suite, ranked</h2>
      <p className={styles.sectionLede}>
        The dashboard ships with every install. Open it after a single test run
        to see what's slow, what's failing, and which tests are doing the most
        work. <Link to="/docs/CLI_REFERENCE#dashboard">Dashboard reference →</Link>
      </p>
      <div className={styles.shotGrid}>
        {SHOTS.map((s) => {
          const src = useBaseUrl(s.src);
          const fallback = useBaseUrl(s.fallback);
          return (
            <a
              key={s.src}
              className={styles.shot}
              href={src}
              target="_blank"
              rel="noreferrer"
            >
              <img
                src={src}
                onError={(e) => {
                  if (e.currentTarget.src !== fallback) {
                    e.currentTarget.src = fallback;
                  }
                }}
                alt={s.caption}
                loading="lazy"
              />
              <div className={styles.shotCaption}>{s.caption}</div>
            </a>
          );
        })}
      </div>
    </section>
  );
}
