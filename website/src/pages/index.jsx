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
            Four steps. The first two are things you already do.
          </p>
          <div className={styles.howFlow}>

            <div className={styles.howStep}>
              <span className={styles.howStepNum}>1</span>
              <h3 className={styles.featureTitle}>Learn once</h3>
              <CodeBlock language="bash">mvn test</CodeBlock>
              <p className={styles.featureBody} style={{ marginTop: '0.6rem' }}>
                Records which source classes each test actually exercises.
                Stored in <code>.test-order/</code> — happens automatically on first run.
              </p>
            </div>

            <div className={styles.howFlowArrow}>→</div>

            <div className={styles.howStep}>
              <span className={styles.howStepNum}>2</span>
              <h3 className={styles.featureTitle}>Make a change</h3>
              <CodeBlock language="bash">{`# edit a source file\n# or add a new test`}</CodeBlock>
            </div>

            <div className={styles.howFlowArrow}>→</div>

            <div className={styles.howStep}>
              <span className={styles.howStepNum}>3</span>
              <h3 className={styles.featureTitle}>Run affected tests</h3>
              <CodeBlock language="bash">mvn test-order:affected test</CodeBlock>
              <div className={styles.howTiers}>
                <div className={styles.howTierAffected}>
                  <span className={styles.howTierLabel}>Affected</span>
                  <span className={styles.howTierDesc}>Touch changed code → run first</span>
                </div>
              </div>
            </div>

            <div className={styles.howFlowArrow}>→</div>

            <div className={styles.howStep}>
              <span className={styles.howStepNum}>4</span>
              <h3 className={styles.featureTitle}>Run remaining</h3>
              <CodeBlock language="bash">mvn test-order:run-remaining test</CodeBlock>
              <div className={styles.howTiers}>
                <div className={styles.howTierRest}>
                  <span className={styles.howTierLabel}>Rest</span>
                  <span className={styles.howTierDesc}>Only if step 3 succeeds</span>
                </div>
              </div>
            </div>

          </div>

          <div className={styles.howAutoNote}>
            <strong>Prefer one command?</strong>{' '}
            <code>mvn test-order:auto test</code> combines all of the above —
            learns on first run, then runs affected tests first and the rest after, automatically.
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
