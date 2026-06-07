# sample-always-learn

Demonstrates running in *permanent* learn mode by pinning
`<mode>learn</mode>` in the plugin configuration. Useful for CI pipelines
that should keep the dependency index continuously fresh, or for projects
where the runtime cost of always learning is acceptable in exchange for
never serving stale rankings.

## Try it

```bash
# Each invocation runs the agent and rewrites the index
mvn test

# Confirm the index was refreshed
mvn test-order:diagnose

# Compare with auto-mode behavior (note: auto-mode would skip learn here)
mvn -Dtestorder.mode=auto test
```
