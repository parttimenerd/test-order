import React from 'react';
import Link from '@docusaurus/Link';
import styles from '../pages/index.module.css';

const FEATURES = [
  {
    icon: '🎯',
    title: 'Catches bugs sooner',
    body: 'EMA-weighted failure history surfaces flaky and recently-broken tests early. Bug-finding tests rise to the top automatically.',
    to: '/docs/SCORING',
  },
  {
    icon: '🧠',
    title: 'Adapts to your codebase',
    body: 'Per-class weights, JaCoCo coverage signals, and a genetic optimizer tune the scoring without manual config.',
    to: '/docs/ARCHITECTURE',
  },
  {
    icon: '🔌',
    title: 'Fits your build',
    body: 'Drop-in Maven and Gradle plugins. No CI changes. Works on existing JUnit 6 / 5 / 4 and TestNG suites.',
    to: '/docs/CI',
  },
];

export default function HomepageFeatures() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>Built for real test suites</h2>
      <p className={styles.sectionLede}>
        Three things test-order does that random ordering, alphabetical
        ordering, and stale heuristics don't.
      </p>
      <div className={styles.featureGrid}>
        {FEATURES.map((f) => (
          <Link
            key={f.title}
            to={f.to}
            className={styles.featureCard}
          >
            <span className={styles.featureIcon} aria-hidden="true">
              {f.icon}
            </span>
            <h3 className={styles.featureTitle}>{f.title}</h3>
            <p className={styles.featureBody}>{f.body}</p>
            <span className={styles.featureArrow} aria-hidden="true">
              Read more →
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
