import React from 'react';
import Link from '@docusaurus/Link';
import styles from '../pages/index.module.css';

const FEATURE_GROUPS = [
  {
    heading: 'Test prioritization',
    items: [
      { label: 'Failure-history scoring (EMA)', to: '/docs/SCORING' },
      { label: 'Code-churn & change detection', to: '/docs/SCORING#change-detection-modes' },
      { label: 'Coverage-based impact analysis (JaCoCo)', to: '/docs/SCORING' },
      { label: 'Speed bonus for fast tests', to: '/docs/SCORING' },
      { label: 'Genetic weight optimizer (auto-tunes per project)', to: '/docs/ARCHITECTURE' },
      { label: 'Tunable weights via dashboard UI', to: '/docs/CLI_REFERENCE#dashboard' },
    ],
  },
  {
    heading: 'Affected-test selection',
    items: [
      { label: 'Run only tests touching changed classes', to: '/docs/DETECT_DEPENDENCIES' },
      { label: 'Offline bytecode instrumentation (no agent)', to: '/docs/GETTING_STARTED#step-2-run-tests-learn-mode' },
      { label: 'Online agent-based instrumentation', to: '/docs/MAVEN_PLUGIN' },
      { label: 'MEMBER-level tracking (field/method)', to: '/docs/CI#tracking-granularity' },
      { label: 'CLASS-level tracking (lower overhead)', to: '/docs/CI#tracking-granularity' },
    ],
  },
  {
    heading: 'Order-dependent test detection',
    items: [
      { label: 'Detect flaky OD tests (iDFlakies-inspired)', to: '/docs/DETECT_DEPENDENCIES' },
      { label: 'Reverse, random, exclusion-probe algorithms', to: '/docs/DETECT_DEPENDENCIES' },
      { label: 'Tuscan-square systematic pair coverage', to: '/docs/DETECT_DEPENDENCIES' },
      { label: 'Binary-search polluter pinpointing', to: '/docs/DETECT_DEPENDENCIES' },
    ],
  },
  {
    heading: 'Dashboard & reporting',
    items: [
      { label: 'Interactive HTML dashboard (Tests / Analytics / Weights)', to: '/docs/CLI_REFERENCE#dashboard' },
      { label: 'APFD timeline and per-run drill-down', to: '/docs/CLI_REFERENCE#dashboard' },
      { label: 'Rank heatmap & failure correlation matrix', to: '/docs/CLI_REFERENCE#dashboard' },
      { label: 'KPI bar: APFD, pass streak, at-risk tests', to: '/docs/CLI_REFERENCE#dashboard' },
      { label: 'Live-reload dashboard server', to: '/docs/MAVEN_PLUGIN' },
    ],
  },
  {
    heading: 'CI & build integration',
    items: [
      { label: 'Maven plugin (prepare / auto / affected / show / diagnose)', to: '/docs/MAVEN_PLUGIN' },
      { label: 'Gradle plugin (same goals)', to: '/docs/GETTING_STARTED#gradle' },
      { label: 'Tiered execution (fast → broad → full)', to: '/docs/CLI_REFERENCE' },
      { label: 'Multi-module Maven reactor support', to: '/docs/MAVEN_PLUGIN' },
      { label: 'GitHub Actions / GitLab CI / Azure Pipelines examples', to: '/docs/CI' },
      { label: 'CI artifact download (mvn test-order:download)', to: '/docs/CI' },
    ],
  },
  {
    heading: 'Framework & language support',
    items: [
      { label: 'JUnit 6 / 5 / 4 / Vintage', to: '/docs/FRAMEWORK_COMPARISON' },
      { label: 'TestNG 7.x+', to: '/docs/FRAMEWORK_COMPARISON' },
      { label: 'Kotest (Kotlin)', to: '/docs/KOTEST' },
      { label: 'Spring Boot test context grouping', to: '/docs/FRAMEWORK_COMPARISON#spring-context-grouping' },
      { label: 'Java 17–26 (all LTS + latest)', to: '/docs/GETTING_STARTED#prerequisites' },
    ],
  },
];

export default function HomepageAllFeatures() {
  return (
    <section className={styles.allFeaturesSection}>
      <div className={styles.allFeaturesInner}>
        <h2 className={styles.sectionTitle}>Everything that ships in the box</h2>
        <p className={styles.sectionLede}>
          Every feature links to the relevant docs section.
        </p>
        <div className={styles.allFeaturesGrid}>
          {FEATURE_GROUPS.map((g) => (
            <div key={g.heading} className={styles.allFeaturesGroup}>
              <h3 className={styles.allFeaturesGroupTitle}>{g.heading}</h3>
              <ul className={styles.allFeaturesList}>
                {g.items.map((item) => (
                  <li key={item.label}>
                    <Link to={item.to}>{item.label}</Link>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
