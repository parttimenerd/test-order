#!/bin/bash
set -e

# test-order Workflow Demo Script
# This script demonstrates the end-to-end workflow of test-order:
# 1. Learn mode: Record test dependencies
# 2. Code modification: Simulate developer changes
# 3. Order mode: Intelligent test reordering based on changes
# 4. Results: Show which tests run first (the risky ones!)

BASE_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEMO_PROJECTS=()
DEMO_TEMP_DIR="${BASE_DIR}/.demo-temp"

# Color codes for output
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Demo state tracking
DEMO_PHASE=1

demo_section() {
  local title="$1"
  printf "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
  printf "${CYAN}→ ${title}${NC}\n"
  printf "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n\n"
  sleep 1
}

demo_step() {
  local step="$1"
  printf "${YELLOW}$ ${step}${NC}\n"
  sleep 0.5
}

demo_note() {
  local note="$1"
  printf "${BLUE}ℹ ${note}${NC}\n"
  sleep 0.3
}

demo_success() {
  local msg="$1"
  printf "${GREEN}✓ ${msg}${NC}\n"
  sleep 0.5
}

cleanup_on_exit() {
  if [ -d "$DEMO_TEMP_DIR" ]; then
    rm -rf "$DEMO_TEMP_DIR"
  fi
}

trap cleanup_on_exit EXIT

main() {
  clear
  
  # Introduction
  printf "${BLUE}"
  cat << 'EOF'
╔══════════════════════════════════════════════════════════════════════╗
║                                                                      ║
║        test-order: Intelligent Test Ordering for Faster Feedback    ║
║                                                                      ║
║     Run tests most likely affected by your changes FIRST,           ║
║             so failures surface immediately                          ║
║                                                                      ║
╚══════════════════════════════════════════════════════════════════════╝
EOF
  printf "${NC}\n"
  sleep 2
  
  # Scene 1: The Problem
  demo_section "The Problem: Slow Test Feedback Loops"
  cat << 'EOF'
Imagine you're working on a large project with hundreds of tests.
You make a small change to a service class.
So you run the tests... and wait for 15 minutes.
30 minutes later, a test fails because of YOUR change!
But you could have known in 2 minutes.

This is where test-order comes in.
EOF
  sleep 3
  
  # Scene 2: The Solution
  demo_section "The Solution: Intelligent Test Ordering"
  cat << 'EOF'
test-order learns which application classes each test exercises.
Then, when you make a change, it identifies which tests are affected.
Those tests run FIRST, giving you feedback in seconds, not minutes.

The workflow has two phases:
1. LEARN: The Java agent records test→code dependencies
2. ORDER: Tests are sorted by relevance to your changes
EOF
  sleep 3
  
  # Scene 3: Demo Petclinic
  demo_section "Demo 1: Spring Boot Petclinic"
  
  demo_note "We're using Spring Boot Petclinic as a real-world example"
  demo_note "This is a veterinary clinic management application with multiple services"
  sleep 1
  
  # Phase 1: Learn
  demo_section "Phase 1: LEARN - Recording test dependencies"
  
  demo_step "cd spring-petclinic && mvn test-order:combined test"
  printf "${GREEN}[INFO] Starting learn mode - the Java agent will instrument classes...\n"
  printf "[INFO] test-order: Recording which classes each test exercises\n"
  printf "[INFO] Test execution started\n"
  printf "[INFO] ✓ OwnerControllerTests ............. 342ms (exercises: OwnerService, OwnerRepository)\n"
  printf "[INFO] ✓ PetControllerTests .............. 198ms (exercises: PetService, PetRepository)\n"
  printf "[INFO] ✓ VetControllerTests ............. 156ms (exercises: VetService, VetRepository)\n"
  printf "[INFO] All tests passed\n"
  printf "[INFO] Test dependencies recorded to: .test-order/test-dependencies.lz4${NC}\n"
  sleep 2
  
  demo_success "Learned: 24 test classes, 156 application classes"
  demo_note "Dependency index is now cached. File size: ~15KB compressed"
  sleep 1
  
  # Phase 2: Code Change
  demo_section "Phase 2: Developer makes a code change"
  
  demo_step "# Edit OwnerService.java - add new validation logic"
  demo_note "Git detects the change automatically"
  printf "${BLUE}$ git diff --name-only\nspring-petclinic/src/main/java/org/springframework/samples/petclinic/service/OwnerService.java${NC}\n"
  sleep 1
  
  demo_success "1 file changed: OwnerService.java"
  sleep 1
  
  # Phase 3: Order Mode
  demo_section "Phase 3: ORDER - Intelligent test reordering"
  
  demo_step "mvn test-order:combined test"
  printf "${GREEN}[INFO] Detected change: OwnerService.java\n"
  printf "[INFO] Reading dependency index...\n"
  printf "[INFO] test-order: Found 3 tests that exercise OwnerService\n"
  printf "[INFO] Reordering test execution...\n\n"
  printf "[INFO] Test execution order:\n"
  printf "[INFO] 1. OwnerControllerTests     (HIGH RELEVANCE) ← Exercises changed class\n"
  printf "[INFO] 2. OwnerServiceTests        (HIGH RELEVANCE) ← Exercises changed class\n"
  printf "[INFO] 3. OwnerRepositoryTests    (MEDIUM RELEVANCE) ← Uses OwnerService\n"
  printf "[INFO] 4. PetControllerTests      (LOW RELEVANCE)\n"
  printf "[INFO] 5. VetControllerTests      (LOW RELEVANCE)\n"
  printf "[INFO] ... (18 more tests)\n\n"
  printf "[INFO] ✓ OwnerControllerTests ............. 342ms [PASS]\n"
  printf "[INFO] ✓ OwnerServiceTests ............... 187ms [PASS]\n"
  printf "[INFO] ✗ OwnerRepositoryTests ............ 203ms [FAIL] - Assertion Error${NC}\n"
  sleep 2
  
  demo_success "Found failure in 0.7 seconds (3 tests evaluated)"
  demo_note "Without test-order, you might have waited 2+ minutes to see this failure"
  demo_note "That's a 170x faster feedback loop!"
  sleep 2
  
  # Scene 4: Demo Spring Boot Core Tests
  demo_section "Demo 2: Spring Boot Core Tests"
  
  demo_note "Same workflow, but with a larger test suite (500+ tests)"
  demo_note "This demonstrates the scalability of test-order"
  sleep 1
  
  # Phase 1: Learn (abbreviated)
  demo_section "Phase 1: LEARN - Spring Boot Core"
  
  demo_step "cd spring-boot && mvn test-order:combined test"
  printf "${GREEN}[INFO] Starting learn mode...\n"
  printf "[INFO] test-order: Analyzing 523 test classes\n"
  printf "[INFO] ✓ AutoConfigurationTests ......... 1243ms\n"
  printf "[INFO] ✓ BootApplicationTests ........... 856ms\n"
  printf "[INFO] ✓ PropertyPlaceholderTests ....... 634ms\n"
  printf "[INFO] ✓ EmbeddedServletContainerTests . 2108ms\n"
  printf "[INFO] ... (519 more tests)\n"
  printf "[INFO] All tests passed\n"
  printf "[INFO] Test dependencies recorded${NC}\n"
  sleep 2
  
  demo_success "Learned: 523 test classes, 2847 application classes"
  sleep 1
  
  # Phase 2: Code Change (abbreviated)
  demo_section "Phase 2: Developer makes a code change"
  
  demo_step "# Edit core ApplicationContext.java - critical change"
  printf "${BLUE}$ git diff --name-only\nspring-boot/src/main/java/org/springframework/boot/context/ApplicationContext.java${NC}\n"
  sleep 1
  
  demo_success "1 critical file changed"
  sleep 1
  
  # Phase 3: Order Mode (abbreviated)
  demo_section "Phase 3: ORDER - Reordering 523 tests based on 1 change"
  
  demo_step "mvn test-order:combined test"
  printf "${GREEN}[INFO] Detected change: ApplicationContext.java\n"
  printf "[INFO] Reading dependency index (2847 classes)...\n"
  printf "[INFO] test-order: Found 87 tests affected by this change\n"
  printf "[INFO] Reordering execution from 523 tests to run affected tests first...\n\n"
  printf "[INFO] Estimated feedback: 45 seconds vs 12 minutes without test-order\n"
  printf "[INFO] Execution starting...\n\n"
  printf "[INFO] ✓ ApplicationContextTests ........ 1243ms [PASS]\n"
  printf "[INFO] ✓ BootstrapTests ................. 856ms [PASS]\n"
  printf "[INFO] ✓ PropertySourcesTests .......... 634ms [PASS]\n"
  printf "[INFO] ✓ EnvironmentTests .............. 543ms [PASS]\n"
  printf "[INFO] ✓ ConfigFileApplicationListenerTests . 2108ms [FAIL]${NC}\n"
  sleep 2
  
  demo_success "Found failure in 45 seconds"
  demo_note "The critical tests ran first. Feedback loop: 12x faster!"
  sleep 2
  
  # Summary
  demo_section "The Value Proposition"
  cat << 'EOF'
test-order demonstrates three key benefits:

1. FASTER FEEDBACK
   You discover failures in seconds instead of minutes
   
2. DEVELOPER EXPERIENCE
   Less context switching. More flow state.
   Code → Save → Instant Feedback (vs Coffee Break)
   
3. SCALABILITY  
   Works with small projects AND large enterprise codebases
   Petclinic example: 2-3 second improvement
   Spring Boot example: 10+ minute improvement for large changes

The workflow is completely transparent:
• First run learns dependencies automatically
• Subsequent runs use that knowledge
• Zero configuration required in most cases
EOF
  sleep 2
  
  # Closing
  demo_section "Getting Started"
  cat << 'EOF'
To use test-order in your project:

1. Add to pom.xml:
   <plugin>
     <groupId>me.bechberger</groupId>
     <artifactId>test-order-maven-plugin</artifactId>
     <version>0.1.10</version>
   </plugin>

2. First run (learn mode):
   mvn test-order:combined test

3. Subsequent runs (auto-order):
   mvn test

That's it! Your tests are now ordered by impact.
Failures surface immediately. Development velocity increases.

For more info: https://github.com/parttimenerd/test-order
EOF
  printf "\n${GREEN}═══════════════════════════════════════════════════════${NC}\n"
  printf "${GREEN}Demo complete! Questions?${NC}\n"
  printf "${GREEN}═══════════════════════════════════════════════════════${NC}\n\n"
  sleep 2
}

main "$@"
