import React from 'react';
import clsx from 'clsx';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import Head from '@docusaurus/Head';
import CodeBlock from '@theme/CodeBlock';

import HomepageHero from '../components/HomepageHero';
import HomepageAffected from '../components/HomepageAffected';
import HomepageWhy from '../components/HomepageWhy';
import HomepageFeatures from '../components/HomepageFeatures';
import HomepageAllFeatures from '../components/HomepageAllFeatures';
import HomepageScreenshots from '../components/HomepageScreenshots';
import HomepageInstall from '../components/HomepageInstall';
import AsciinemaPlayer from '../components/AsciinemaPlayer';

import styles from './index.module.css';

const HOW_STEPS = [
  {
    n: 1,
    title: 'Learn once',
    code: 'mvn test',
    note: 'First run records which source classes each test exercises.',
  },
  {
    n: 2,
    title: 'Make a change',
    code: '# edit any source file',
    note: 'test-order detects what changed via git diff.',
  },
  {
    n: 3,
    title: 'Run affected tests first',
    code: 'mvn test-order:auto test',
    note: 'Affected tests run first. Failures surface in seconds, not minutes.',
  },
];

export default function Home() {
  const structuredData = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'test-order',
    description:
      'test-order learns which of your tests actually catch bugs and runs those first. Maven and Gradle plugin for JUnit and TestNG that prioritizes tests by failure history, code churn, and coverage to give faster CI feedback.',
    applicationCategory: 'DeveloperApplication',
    operatingSystem: 'Cross-platform (JVM)',
    url: 'https://parttimenerd.github.io/test-order/',
    codeRepository: 'https://github.com/parttimenerd/test-order',
    license: 'https://opensource.org/licenses/MIT',
    programmingLanguage: 'Java',
    offers: {
      '@type': 'Offer',
      price: '0',
      priceCurrency: 'USD',
    },
    author: {
      '@type': 'Person',
      name: 'Johannes Bechberger',
      url: 'https://github.com/parttimenerd',
    },
    keywords:
      'test prioritization, regression testing, CI optimization, JUnit, TestNG, Maven, Gradle, test impact analysis, fail-fast',
  };

  return (
    <Layout
      title="test-order — Stop running tests that will never fail"
      description="test-order learns which of your tests actually catch bugs — and runs those first. Maven & Gradle plugin for JUnit / TestNG. 20+ real bugs caught across 30+ OSS projects."
    >
      <Head>
        <link rel="canonical" href="https://parttimenerd.github.io/test-order/" />
        <script type="application/ld+json">
          {JSON.stringify(structuredData)}
        </script>
      </Head>

      <HomepageHero />

      <main>
        <section
          id="hero-cast"
          className={styles.heroCast}
          aria-label="Hello-world demo"
        >
          <div className={styles.heroCastFrame}>
            <AsciinemaPlayer
              src="/casts/demo-hello-world.cast"
              autoPlay
              loop
              cols={120}
              rows={28}
            />
          </div>
          <p className={styles.heroCastCaption}>
            <Link to="/docs/DEMOS">More demos →</Link>{' '}
            ·{' '}
            <Link to="/docs/GETTING_STARTED">Run this yourself in 30 seconds →</Link>
          </p>
        </section>

        <HomepageAffected />

        <HomepageWhy />

        <HomepageFeatures />

        <HomepageAllFeatures />

        <HomepageScreenshots />

        <HomepageInstall />

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>How it works</h2>
          <p className={styles.sectionLede}>
            One learn run. Then affected tests always go first.
          </p>
          <div className={styles.howGrid}>
            {HOW_STEPS.map((s) => (
              <div key={s.n} className={styles.howStep}>
                <span className={styles.howStepNum}>{s.n}</span>
                <h3 className={styles.featureTitle}>{s.title}</h3>
                <CodeBlock language="bash">{s.code}</CodeBlock>
                <p className={styles.featureBody} style={{ marginTop: '0.6rem' }}>
                  {s.note}
                </p>
              </div>
            ))}
          </div>
        </section>

        <section className={clsx(styles.closingCta)}>
          <h2>Stop running tests that will never fail.</h2>
          <Link
            className="button button--primary button--lg"
            to="/docs/GETTING_STARTED"
          >
            Read the getting-started guide →
          </Link>
        </section>
      </main>
    </Layout>
  );
}
