
## Plan: JUnit 5 Test Order Priority System

Build a Maven multi-module project (`me.bechberger.testorder`) with 4 modules: a Java agent that records which classes each test uses (learn mode, adapted from dead-code-agent), a JUnit 5 extension jar with `ClassOrderer` + `TestExecutionListener`, a Maven plugin that orchestrates both modes, and a CLI tool for index management and change detection. Targets JDK 17+. Supports Spring Boot projects (tested against spring-petclinic). V1 uses simple text-based index; Roaring Bitmap compression deferred to V2.

---

### Phase 1: Project Skeleton
1. Parent pom.xml at workspace root (`me.bechberger:test-order-parent`), `<maven.compiler.source/target>17`
2. Four module directories: `test-order-agent`, `test-order-junit`, `test-order-maven-plugin`, `test-order-example`

### Phase 2: Agent Module (`test-order-agent`)
Adapted from dead-code-agent source code. Targets JDK 17.

3. `agent/Agent.java` — `premain` entry: parse options, extract runtime jar to bootstrap classpath, register transformer (follows Main.java)
4. `agent/AgentOptions.java` — parse `key=value` agent args (`outputDir=<dir>`) (follows AgentOptions.java)
5. `agent/ClassTransformer.java` — `ClassFileTransformer` using javassist; instruments `<clinit>` to call `UsageStore.getInstance().recordUsage("com.example.Foo")`. Skips JDK + own classes (follows ClassTransformer.java)
   - **Spring Boot support**: also skip Spring framework internals (`org/springframework/`) from instrumentation to avoid noise, but DO instrument application classes loaded by Spring (controllers, services, repositories, entities)
   - Skip common framework packages: `org/springframework/`, `org/hibernate/`, `org/apache/tomcat/`, `jakarta/`, `javax/`
   - Keep a configurable `includePackages` option (agent arg) — when set, ONLY instrument classes matching those prefixes (e.g., `includePackages=org.springframework.samples.petclinic`) — ensures clean dependency graph for Spring Boot apps
6. `agent/runtime/UsageStore.java` — bootstrap classpath singleton with `ConcurrentHashMap.newKeySet()`, `recordUsage()`, shutdown hook writes `<outputDir>/<testClass>.deps`. Communication with listener via `System.getProperty("testorder.current.testclass")` (simplified from Store.java)
7. pom.xml + pom_runtime.xml — fat jar with javassist, embedded runtime jar (follows pom.xml build pattern). `<maven.compiler.source/target>17`

### Phase 3: JUnit Extension Module (`test-order-junit`)

8. `TelemetryListener.java` — implements `TestExecutionListener` (auto-discovered via `META-INF/services`). On `testPlanExecutionStarted()`, extracts test class name, sets `System.setProperty("testorder.current.testclass", name)`. Only activates when `testorder.learn=true`.
   - **Spring Boot support**: handles `@SpringBootTest` classes — the listener still captures the test class name correctly; the agent captures all application classes the Spring context initializes for that test
9. `PriorityClassOrderer.java` — implements `ClassOrderer`. Loads index + changed classes list from system properties. Scores each test class by `|deps ∩ changedClasses|`, sorts descending. Aggregates `@Nested` class deps to parent. Graceful no-op if no index.
10. `DependencyMap.java` — data model + I/O. V1 text format: `testClass\tclass1,class2,...` per line. Methods: `load()`, `save()`, `aggregate(depsDir)`, `getAffectedTests(changedClasses)`.

### Phase 3b: Change Detection (`test-order-junit` — `me.bechberger.testorder.changes` package)

11. **`FileHashStore.java`** — compressed hash table mapping source file paths → SHA-256 hashes
    - `Map<String, String>` stored as GZIP-compressed binary file (`.test-order-hashes.gz`)
    - `static FileHashStore scan(Path sourceRoot)` — walks `src/main/java/**/*.java`, computes SHA-256 per file
    - `static FileHashStore load(Path hashFile)` — reads compressed hash file
    - `void save(Path hashFile)` — writes compressed hash file
    - `Set<String> getChangedFiles(FileHashStore previous)` — returns paths of files whose hash differs or are new/deleted
    - `static Set<String> filesToClassNames(Set<String> filePaths, Path sourceRoot)` — converts `src/main/java/com/example/Foo.java` → `com.example.Foo`

12. **`GitChangeDetector.java`** — detects changes via git
    - `static Set<String> changedSinceCommit(Path projectRoot, String commitRef)` — runs `git diff --name-only <ref> HEAD -- src/main/java/`, filters `*.java`, converts to FQCNs
    - `static Set<String> changedSinceLastCommit(Path projectRoot)` — shortcut for `changedSinceCommit(root, "HEAD~1")`
    - `static Set<String> uncommittedChanges(Path projectRoot)` — runs `git diff --name-only HEAD -- src/main/java/` + `git diff --cached --name-only -- src/main/java/`
    - Uses `ProcessBuilder` to invoke git, parses stdout

13. **`ChangeDetector.java`** — unified interface
    - `enum Mode { SINCE_LAST_RUN, SINCE_LAST_COMMIT, UNCOMMITTED, EXPLICIT }`
    - `static Set<String> detect(Mode mode, Path projectRoot, Path sourceRoot, Path hashFile, String explicitClasses)` — dispatches to appropriate implementation:
      - `SINCE_LAST_RUN`: loads `FileHashStore` from `.test-order-hashes.gz`, compares with fresh scan, updates hash file
      - `SINCE_LAST_COMMIT`: delegates to `GitChangeDetector.changedSinceLastCommit()`
      - `UNCOMMITTED`: delegates to `GitChangeDetector.uncommittedChanges()`
      - `EXPLICIT`: parses comma-separated list

14. **`Tool.java`** — picocli CLI (expanded from original plan):
    - `aggregate` command: reads `.deps` dir → writes index file
    - `affected` command: given changed classes + index → prints affected test classes
    - `stats` command: prints index statistics (test count, class count, avg deps/test)
    - `hash-snapshot` command: scans source tree, writes `.test-order-hashes.gz`
    - `changed` command: detects changed files using specified mode
      - `--mode=since-last-run|since-last-commit|uncommitted|explicit`
      - `--source-root=src/main/java` (default)
      - `--hash-file=.test-order-hashes.gz` (default)
      - `--classes=com.Foo,com.Bar` (for explicit mode)
      - Outputs one changed class FQCN per line
    - `run` command: convenience combo — detect changes + query index → print affected tests

### Phase 4: Maven Plugin (`test-order-maven-plugin`)

15. `PrepareMojo.java` — single `prepare` goal bound to `process-test-classes`:
    - **Auto mode** (default): `-Dtestorder.learn=true` → learn; index exists → order; else no-op
    - **Learn mode**: resolves agent+junit jars, modifies Surefire `Xpp3Dom`: `argLine=@{argLine} -javaagent:<agent-path>=outputDir=<deps-dir>[,includePackages=<pkgs>]`, `reuseForks=false`, `forkCount=1`, adds junit jar to classpath
    - **Order mode**: adds junit jar to classpath, writes `junit-platform.properties` with orderer FQCN, passes index path + changed classes as system properties
    - New configuration parameter: `changeMode` — `since-last-run` | `since-last-commit` | `uncommitted` | `explicit` | `auto` (default: `auto`)
      - `auto`: if `-Dtestorder.changed.classes` set → explicit; else tries `since-last-run`; falls back to `since-last-commit`
    - New parameter: `includePackages` — comma-separated prefixes to instrument (e.g., `org.springframework.samples.petclinic`). Passed to agent.
    - New parameter: `hashFile` — path to `.test-order-hashes.gz` (default: `${project.basedir}/.test-order-hashes.gz`)
    - After learn mode test run: automatically calls `ChangeDetector` to snapshot current file hashes
16. `AggregateMojo.java` — `aggregate` goal: reads `.deps` files → writes merged `test-dependencies.idx`
17. `SnapshotMojo.java` — `snapshot` goal: scans source tree → writes `.test-order-hashes.gz` (can be run standalone)

### Phase 5: Example Projects

#### 5a: Minimal Example (`test-order-example`)
18. Three app classes: `Calculator` (uses `MathHelper`), `StringUtils`, `MathHelper`
19. Two test classes: `CalculatorTest` (deps: Calculator+MathHelper), `StringUtilsTest` (deps: StringUtils)
20. Plugin configured in pom.xml

#### 5b: Spring Boot Example (spring-petclinic)
21. Add test-order-maven-plugin to `spring-petclinic/pom.xml` as a profile:
    ```xml
    <profile>
      <id>test-order</id>
      <build>
        <plugins>
          <plugin>
            <groupId>me.bechberger</groupId>
            <artifactId>test-order-maven-plugin</artifactId>
            <configuration>
              <includePackages>org.springframework.samples.petclinic</includePackages>
            </configuration>
            <executions>
              <execution><goals><goal>prepare</goal></goals></execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
    ```
22. Usage against petclinic (17 test classes, 30 app classes):
    ```bash
    # Learn: record deps (reuseForks=false, agent attached)
    mvn test -Ptest-order -Dtestorder.learn=true
    mvn test-order:aggregate -Ptest-order

    # Order with auto-detect (hash-based):
    # edit OwnerController.java...
    mvn test -Ptest-order
    # → OwnerControllerTests runs first

    # Order with git-based detection:
    mvn test -Ptest-order -Dtestorder.changeMode=since-last-commit
    ```

### Phase 6: Tests

23. `DependencyMapTest` — load/save/aggregate round-trip, affected-tests query
24. `PriorityClassOrdererTest` — mock `ClassOrdererContext`; verify changed-class tests sorted first
25. `TelemetryListenerTest` — verify system property set only in learn mode
26. `FileHashStoreTest` — scan → save → modify file → rescan → assert changed detected; round-trip serialization of compressed format
27. `GitChangeDetectorTest` — test with temp git repo: init, commit, modify, assert correct changed files detected
28. `ChangeDetectorTest` — integration of modes: since-last-run, since-last-commit, explicit
29. `PrepareMojoTest` — verify Surefire Xpp3Dom modifications with `maven-plugin-testing-harness`
30. Minimal example as end-to-end integration test
31. Spring-petclinic as real-world integration test (manual/CI)

---

**Verification**
1. `mvn clean install` from parent — all modules compile (JDK 17)
2. `DependencyMapTest` — write → read → assert equality
3. `PriorityClassOrdererTest` — 3 test classes, 1 matching changed class → assert it sorts first
4. `FileHashStoreTest` — scan dir, modify one file, rescan → exactly that file reported as changed
5. `GitChangeDetectorTest` — temp repo, commit, modify file → correct FQCN returned
6. `PrepareMojoTest` — verify argLine, reuseForks, includePackages, changeMode modifications
7. Example: `mvn test -Dtestorder.learn=true` → `.deps` files exist
8. Example: `mvn test-order:aggregate` → `test-dependencies.idx` correct
9. Example: modify file → `mvn test` → affected test runs first (hash-based detection)
10. Petclinic: `mvn test -Ptest-order -Dtestorder.learn=true` → telemetry captured for all 17 test classes
11. Petclinic: modify `OwnerController.java` → `mvn test -Ptest-order` → `OwnerControllerTests` runs first

**Decisions**
- V1 text format; Roaring Bitmaps deferred to V2
- System property communication between TelemetryListener ↔ UsageStore (no cross-module compile dependency)
- `@{argLine}` late replacement to avoid clobbering JaCoCo/other agents
- JDK 17 baseline — matches Spring Boot 4.x / petclinic requirements
- Spring Boot support: `includePackages` filter limits agent to app classes only (avoids instrumenting Spring/Hibernate internals which would bloat the dependency graph and slow learn mode)
- Three change detection modes: hash-based (`since-last-run`), git-based (`since-last-commit`, `uncommitted`), and `explicit`
- Hash store uses GZIP-compressed binary for efficient storage (typically <10KB for most projects)
- `auto` change mode: explicit → hash-based → git-based fallback chain
- Scope: no multi-module aggregation in V1