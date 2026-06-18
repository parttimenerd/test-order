import React from 'react';
import Link from '@docusaurus/Link';
import CodeBlock from '@theme/CodeBlock';
import styles from '../pages/index.module.css';

const SNIPPET = `# Run only the tests affected by your uncommitted changes
mvn test-order:affected test

# or, with Gradle:
./gradlew testOrderAffected`;

export default function HomepageAffected() {
  return (
    <section className={styles.affectedSection} aria-label="Affected-test selection">
      <div className={styles.affectedInner}>
        <h2 className={styles.sectionTitle}>
          Skip the tests your change can&apos;t break.
        </h2>
        <p className={styles.affectedBody}>
          What if your build pipeline could predict which tests actually matter
          for a given change? test-order{' '}
          <Link to="/docs/DETECT_DEPENDENCIES">
            tracks which parts of your application
          </Link>{' '}
          are exercised by which tests, so when you change a class it can run{' '}
          <strong>only</strong> the tests that touch it — giving you useful
          feedback within <strong>seconds</strong> rather than minutes. Pair it
          with <Link to="/docs/SCORING">priority scoring</Link> to surface
          likely-failing tests first.
        </p>

        <div className={styles.affectedCode}>
          <CodeBlock language="bash">{SNIPPET}</CodeBlock>
        </div>

        <div className={styles.affectedLinks}>
          <Link to="/docs/DETECT_DEPENDENCIES">
            How affected detection works →
          </Link>
          <Link to="/docs/CI">CI integration patterns →</Link>
          <Link to="/docs/CHEAT_SHEET">Cheat sheet →</Link>
        </div>
      </div>
    </section>
  );
}
