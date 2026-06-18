import React from 'react';
import Link from '@docusaurus/Link';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import CodeBlock from '@theme/CodeBlock';
import styles from '../pages/index.module.css';

const MAVEN = `<!-- pom.xml — add the Sonatype snapshot repository -->
<repositories>
  <repository>
    <id>ossrh-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
<pluginRepositories>
  <pluginRepository>
    <id>ossrh-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <snapshots><enabled>true</enabled></snapshots>
  </pluginRepository>
</pluginRepositories>

<!-- pom.xml — add the plugin -->
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <extensions>true</extensions>
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
</plugin>`;

const MAVEN_SETTINGS = `<!-- ~/.m2/settings.xml — lets you use the short mvn test-order:show prefix -->
<settings>
  <pluginGroups>
    <pluginGroup>me.bechberger</pluginGroup>
  </pluginGroups>
</settings>`;

const GRADLE_GROOVY = `// settings.gradle — add the snapshot repository
pluginManagement {
    repositories {
        maven {
            url 'https://central.sonatype.com/repository/maven-snapshots/'
            mavenContent { snapshotsOnly() }
        }
        gradlePluginPortal()
    }
}

// build.gradle
plugins {
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT'
}`;

const GRADLE_KOTLIN = `// settings.gradle.kts — add the snapshot repository
pluginManagement {
    repositories {
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
        }
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    id("me.bechberger.test-order") version "0.0.1-SNAPSHOT"
}`;

export default function HomepageInstall() {
  return (
    <section className={styles.installSection} aria-label="Install snippet">
      <div className={styles.installInner}>
        <h2 className={styles.sectionTitle}>Add it to your project.</h2>
        <p className={styles.sectionLede}>
          One block in your build file. No code changes. No CI changes.
        </p>

        <div className={styles.installTabs}>
          <Tabs groupId="build-tool" queryString>
            <TabItem value="maven" label="Maven" default>
              <CodeBlock language="xml">{MAVEN}</CodeBlock>
              <p style={{ marginTop: '0.75rem', fontSize: '0.9rem' }}>
                Also add to <code>~/.m2/settings.xml</code> (one-time, enables
                the short <code>mvn test-order:show</code> prefix):
              </p>
              <CodeBlock language="xml">{MAVEN_SETTINGS}</CodeBlock>
            </TabItem>
            <TabItem value="gradle" label="Gradle (Groovy)">
              <CodeBlock language="groovy">{GRADLE_GROOVY}</CodeBlock>
            </TabItem>
            <TabItem value="gradle-kts" label="Gradle (Kotlin)">
              <CodeBlock language="kotlin">{GRADLE_KOTLIN}</CodeBlock>
            </TabItem>
          </Tabs>
        </div>

        <p className={styles.installFootnote}>
          Then just <code>mvn test</code> (or <code>./gradlew test</code>) —
          the plugin learns from each run.
        </p>

        <p className={styles.installFootnote}>
          <em>
            Distributed as <code>0.0.1-SNAPSHOT</code> via the{' '}
            <Link
              to="https://central.sonatype.com/repository/maven-snapshots/"
              target="_blank"
              rel="noopener"
            >
              Sonatype Central snapshot repository
            </Link>
            . Maven Central stable release in progress.
          </em>{' '}
          Full setup:{' '}
          <Link to="/docs/MAVEN_PLUGIN">Maven →</Link>
          <span aria-hidden="true"> · </span>
          <Link to="/docs/GETTING_STARTED#gradle">Gradle →</Link>
        </p>
      </div>
    </section>
  );
}
