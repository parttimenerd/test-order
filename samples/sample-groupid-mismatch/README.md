# sample-groupid-mismatch

A regression sample where the project's `groupId` (`org.acme`) does not match
the package layout (`com.myapp`). Earlier versions of the dependency walker
keyed off the artifact coordinates and missed classes outside the declared
group. This sample exists to verify that learn mode correctly detects and
records dependencies regardless of the groupId/package alignment.

## Try it

```bash
# Learn mode should still capture deps for com.myapp.* classes
mvn -Dtestorder.mode=learn test

# Confirm the index has entries for all four test classes
mvn test-order:dump
```
