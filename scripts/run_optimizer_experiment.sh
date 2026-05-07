#!/usr/bin/env bash
# Minimal optimizer experiment — writes results to target/experiment-results.txt
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLE="$ROOT/samples/sample-basic"
SRC="$SAMPLE/src/main/java/com/myapp"
OUT="$ROOT/target/experiment-results.txt"

log() { echo "$*" | tee -a "$OUT"; }
run_tests() {
    local mode="$1" label="$2"
    log "--- Run: $label (mode=$mode)"
    cd "$SAMPLE"
    mvn clean test -Dspotless.check.skip=true -Dtestorder.mode="$mode" 2>&1 | \
        grep --line-buffered -E '\[test-order\]|Tests run:|FAILURE|BUILD' | tee -a "$OUT"
    log "--- Done: $label"
}

backup() { cp -p "$1" "$1.bak"; }
restore() { [[ -f "$1.bak" ]] && mv "$1.bak" "$1" && touch "$1"; }

# Cleanup trap
cleanup() {
    for f in "$SRC"/util/Validator.java "$SRC"/util/StringUtils.java \
             "$SRC"/model/Product.java "$SRC"/model/User.java \
             "$SRC"/service/OrderService.java; do
        [[ -f "$f.bak" ]] && mv "$f.bak" "$f" && touch "$f"
    done
}
trap cleanup EXIT

# Init
mkdir -p "$(dirname "$OUT")"
> "$OUT"
log "=== Optimizer Experiment ==="
log "Started: $(date)"

# Clean
cd "$SAMPLE" && rm -rf .test-order target
log "State cleared"

# Phase 1: learn
run_tests learn "Phase1: baseline learn"

# Phase 2: bug Validator.isValidEmail
backup "$SRC/util/Validator.java"
sed -i '' 's/return email != null && email.contains("@") && email.contains(".");/return false;/' "$SRC/util/Validator.java"
run_tests order "Phase2: bug Validator.isValidEmail"
restore "$SRC/util/Validator.java"

# Phase 3: all pass
run_tests order "Phase3: all pass"

# Phase 4: bug Product.applyDiscount
backup "$SRC/model/Product.java"
sed -i '' 's|return price \* (1 - percent / 100.0);|return price * percent;|' "$SRC/model/Product.java"
run_tests order "Phase4: bug Product.applyDiscount"
restore "$SRC/model/Product.java"

# Phase 5: bug StringUtils.capitalize
backup "$SRC/util/StringUtils.java"
sed -i '' 's/return s.substring(0, 1).toUpperCase() + s.substring(1);/return s.toLowerCase();/' "$SRC/util/StringUtils.java"
run_tests order "Phase5: bug StringUtils.capitalize"
restore "$SRC/util/StringUtils.java"

# Phase 6: bug User.isAdult
backup "$SRC/model/User.java"
sed -i '' 's/return age >= 18;/return age >= 99;/' "$SRC/model/User.java"
run_tests order "Phase6: bug User.isAdult"
restore "$SRC/model/User.java"

# Phase 7: all pass
run_tests order "Phase7: all pass"

# Phase 8: bug Validator.isValidName
backup "$SRC/util/Validator.java"
sed -i '' 's/return name != null && !name.isBlank() && name.length() <= 100;/return false;/' "$SRC/util/Validator.java"
run_tests order "Phase8: bug Validator.isValidName"
restore "$SRC/util/Validator.java"

# Phase 9: bug OrderService.getTotal
backup "$SRC/service/OrderService.java"
sed -i '' 's/return cart.stream().mapToDouble(Product::getPrice).sum();/return 0.0;/' "$SRC/service/OrderService.java"
run_tests order "Phase9: bug OrderService.getTotal"
restore "$SRC/service/OrderService.java"

# Phase 10: final learn
run_tests learn "Phase10: final learn"

# Optimize
log ""
log "=== Running optimizer ==="
cd "$SAMPLE"
mvn test-order:optimize -Dspotless.check.skip=true 2>&1 | tee -a "$OUT"

log ""
log "=== Running diagnose ==="
cd "$SAMPLE"
mvn test-order:diagnose -Dspotless.check.skip=true 2>&1 | tee -a "$OUT"

log ""
log "=== Experiment complete ==="
log "Finished: $(date)"
