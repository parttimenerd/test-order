# langchain4j

## Description
LangChain4j - LLM framework

## Test Results
- Tests run: 0
- Failures: 0
- Errors: 0
- Skipped: 0
- Execution time: 106s
- Exit code: 1

## Test Output
```
[INFO] LangChain4j :: Integration :: Pinecone 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Qdrant 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Tablestore 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Vespa 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Weaviate 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: Amazon S3 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: Azure Blob Storage 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: GitHub 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: Selenium 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: Playwright 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: Tencent COS 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Loader :: Google Cloud Storage 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Parser :: Apache PDFBox 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Parser :: Apache POI 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Parser :: Markdown 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Document Parser :: YAML 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: GraalVM Polyglot/Truffle 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Judge0 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Azure ACADS 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Web Search Engine :: Google Custom Search 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Web Search Engine :: Tavily 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Web Search Engine :: SearchApi 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Embedding Store Filter Parser :: SQL 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Guardrails 1.14.0-beta24-SNAPSHOT ... SKIPPED
[INFO] LangChain4j :: Experimental :: SQL 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Experimental :: Hibernate 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Skills 1.14.0-beta24-SNAPSHOT ....... SKIPPED
[INFO] LangChain4j :: Experimental :: Skills :: Shell 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Agentic Framework 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Agentic Framework :: A2A Integration 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Agentic Framework :: MCP Integration 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Agentic Framework :: Agentic Patterns 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Observability :: Micrometer 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Observation API 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests :: Class Instance Loader 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests :: Class Instance Loader :: Spring 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests :: Class Instance Loader :: Quarkus 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests :: Class Metadata Provider 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests :: Class Metadata Provider :: Spring 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration Tests :: Guardrails 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Internal :: Documentation Chatbot Updater 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Integration :: Jlama 1.14.0-beta24-SNAPSHOT SKIPPED
[INFO] LangChain4j :: Aggregator 1.14.0-beta24-SNAPSHOT ... SKIPPED
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:45 min
[INFO] Finished at: 2026-04-21T15:35:01+02:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project langchain4j-core: 
[ERROR] 
[ERROR] See /Users/i560383_1/code/experiments/test-order/langchain4j/langchain4j-core/target/surefire-reports for the individual test results.
[ERROR] See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] There was an error in the forked process
[ERROR] java.util.ServiceConfigurationError: org.junit.platform.launcher.TestExecutionListener: Provider me.bechberger.testorder.TelemetryListener not found
[ERROR] org.apache.maven.surefire.booter.SurefireBooterForkException: There was an error in the forked process
[ERROR] java.util.ServiceConfigurationError: org.junit.platform.launcher.TestExecutionListener: Provider me.bechberger.testorder.TelemetryListener not found
[ERROR] 	at org.apache.maven.plugin.surefire.booterclient.ForkStarter.fork(ForkStarter.java:628)
[ERROR] 	at org.apache.maven.plugin.surefire.booterclient.ForkStarter.run(ForkStarter.java:285)
[ERROR] 	at org.apache.maven.plugin.surefire.booterclient.ForkStarter.run(ForkStarter.java:250)
[ERROR] 	at org.apache.maven.plugin.surefire.AbstractSurefireMojo.executeProvider(AbstractSurefireMojo.java:1336)
[ERROR] 	at org.apache.maven.plugin.surefire.AbstractSurefireMojo.executeAfterPreconditionsChecked(AbstractSurefireMojo.java:1134)
[ERROR] 	at org.apache.maven.plugin.surefire.AbstractSurefireMojo.execute(AbstractSurefireMojo.java:968)
[ERROR] 	at org.apache.maven.plugin.DefaultBuildPluginManager.executeMojo(DefaultBuildPluginManager.java:126)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor.doExecute2(MojoExecutor.java:328)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor.doExecute(MojoExecutor.java:316)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:212)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:174)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor.access$000(MojoExecutor.java:75)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor$1.run(MojoExecutor.java:162)
[ERROR] 	at org.apache.maven.plugin.DefaultMojosExecutionStrategy.execute(DefaultMojosExecutionStrategy.java:39)
[ERROR] 	at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:159)
[ERROR] 	at org.apache.maven.lifecycle.internal.LifecycleModuleBuilder.buildProject(LifecycleModuleBuilder.java:105)
[ERROR] 	at org.apache.maven.lifecycle.internal.LifecycleModuleBuilder.buildProject(LifecycleModuleBuilder.java:73)
[ERROR] 	at org.apache.maven.lifecycle.internal.builder.singlethreaded.SingleThreadedBuilder.build(SingleThreadedBuilder.java:53)
[ERROR] 	at org.apache.maven.lifecycle.internal.LifecycleStarter.execute(LifecycleStarter.java:118)
[ERROR] 	at org.apache.maven.DefaultMaven.doExecute(DefaultMaven.java:261)
[ERROR] 	at org.apache.maven.DefaultMaven.doExecute(DefaultMaven.java:173)
[ERROR] 	at org.apache.maven.DefaultMaven.execute(DefaultMaven.java:101)
[ERROR] 	at org.apache.maven.cli.MavenCli.execute(MavenCli.java:903)
[ERROR] 	at org.apache.maven.cli.MavenCli.doMain(MavenCli.java:280)
[ERROR] 	at org.apache.maven.cli.MavenCli.main(MavenCli.java:203)
[ERROR] 	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
[ERROR] 	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
[ERROR] 	at org.codehaus.plexus.classworlds.launcher.Launcher.launchEnhanced(Launcher.java:255)
[ERROR] 	at org.codehaus.plexus.classworlds.launcher.Launcher.launch(Launcher.java:201)
[ERROR] 	at org.codehaus.plexus.classworlds.launcher.Launcher.mainWithExitCode(Launcher.java:361)
[ERROR] 	at org.codehaus.plexus.classworlds.launcher.Launcher.main(Launcher.java:314)
[ERROR] 
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoExecutionException
[ERROR] 
[ERROR] After correcting the problems, you can resume the build with the command
[ERROR]   mvn <args> -rf :langchain4j-core
```
