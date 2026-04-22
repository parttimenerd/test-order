# PHASE 5: Advanced Docker Container Scenarios Report

**Date:** 2026-04-21T15:49:04.162057
**Test Type:** Advanced Docker Scenarios & Race Conditions
**Scenarios:** 6
**Bugs Found:** 2

## Test Results

- ✅ Concurrent Cache Writes - All 5 containers completed (last-write-wins)
- ✅ Partial Write Detection - Valid cache loaded
- ❌ Layer Caching Effects - Cache not preserved across layers
- ✅ Disk Space Exhaustion - Successfully wrote cache in available space
- ❌ Lockfile Deadlock - No proper locking mechanism
- ✅ User Permissions - Cache writable by current user

## Bugs Found (2)

### P5-1001: Cache Loss on Docker Layer Rebuild
- **Severity:** 🔴 CRITICAL
- **Scenario:** Docker image rebuild with .test-order in app layer
- **Issue:** Cache from previous layer not available in new layer
- **Reproduction:**
  1. Build Docker image with cache in layer
2. Change source code
3. Rebuild - cache from old layer gone

### P5-1002: Cache Lockfile Race Condition (No Lock)
- **Severity:** 🔴 CRITICAL
- **Scenario:** Multiple containers with no proper locking
- **Issue:** Both containers acquired lock simultaneously
- **Reproduction:**
  1. Container A opens lockfile
2. Container B opens lockfile
3. Both proceed concurrently

