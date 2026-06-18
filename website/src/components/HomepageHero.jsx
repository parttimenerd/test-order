import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import styles from '../pages/index.module.css';

const BADGES = [
  { label: 'Maven plugin', to: '/docs/MAVEN_PLUGIN' },
  { label: 'Gradle plugin', to: '/docs/GETTING_STARTED#gradle' },
  { label: 'JUnit 6 / 5 / 4 / Vintage', to: '/docs/FRAMEWORK_COMPARISON' },
  { label: 'TestNG', to: '/docs/FRAMEWORK_COMPARISON' },
  { label: 'Spring Boot ready', to: '/docs/FRAMEWORK_COMPARISON#spring-context-grouping' },
];

export default function HomepageHero() {
  return (
    <header className={styles.heroBanner}>
      <h1 className={styles.slogan}>Stop running tests that will never fail.</h1>
      <p className={styles.subhead}>
        Run <em>only the tests affected by your change</em> — and the ones most
        likely to fail — <em>first</em>.
        <br />
        Feedback in seconds, not minutes. Same coverage. Lower CI bills.
      </p>

      <div className={styles.ctaRow}>
        <Link
          className={clsx('button', 'button--primary', 'button--lg')}
          to="/docs/GETTING_STARTED"
        >
          Get started →
        </Link>
        <Link
          className={clsx('button', 'button--secondary', 'button--lg')}
          to="https://github.com/parttimenerd/test-order"
        >
          View on GitHub
        </Link>
        <a
          className={clsx('button', 'button--outline', 'button--lg')}
          href="#hero-cast"
        >
          ▶ Watch the demo
        </a>
      </div>

      <p className={styles.reassurance}>
        30-second install · MIT licensed · Java 17–26 · No CI changes
      </p>

      <div className={styles.badgeRow}>
        {BADGES.map((b) => (
          <Link key={b.label} to={b.to} className={styles.badge}>
            {b.label}
          </Link>
        ))}
      </div>
    </header>
  );
}
