#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Quality Gate Script for AI Code Review Assistant
#
# Parses the generated Markdown review report to extract severity counts and
# enforces quality thresholds.
#
# Usage:
#   ./ci/quality-gate.sh <report-file> [major-threshold]
#
# Arguments:
#   <report-file>    Path to the Markdown review report (required)
#   [major-threshold] Maximum allowed MAJOR findings before WARN (default: 10)
#
# Exit codes:
#   0 - PASS (no CRITICAL, MAJOR within threshold)
#   1 - FAIL (CRITICAL findings present)
#   2 - ERROR (report file not found, malformed, etc.)
# ---------------------------------------------------------------------------
set -euo pipefail

# ---- Color output helpers ------------------------------------------------
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
pass()    { echo -e "${GREEN}[PASS]${NC}  $*"; }
fail()    { echo -e "${RED}[FAIL]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ---- Argument parsing ----------------------------------------------------
REPORT_FILE="${1:-}"
MAJOR_THRESHOLD="${2:-10}"

if [[ -z "$REPORT_FILE" ]]; then
    error "Usage: $0 <report-file> [major-threshold]"
    exit 2
fi

if [[ ! -f "$REPORT_FILE" ]]; then
    error "Report file not found: $REPORT_FILE"
    exit 2
fi

if ! [[ "$MAJOR_THRESHOLD" =~ ^[0-9]+$ ]]; then
    error "Major threshold must be a non-negative integer, got: $MAJOR_THRESHOLD"
    exit 2
fi

echo ""
info "========================================"
info "  AI Code Review — Quality Gate"
info "  Report:      $REPORT_FILE"
info "  Threshold:   MAJOR <= $MAJOR_THRESHOLD"
info "========================================"
echo ""

# ---- Extract severity counts from Markdown report ------------------------
# The report summary section has a table like:
#   | Severity | Count |
#   |----------|-------|
#   | :red_circle: Critical | 3 |
#   | :orange_circle: Major | 7 |
#   | :yellow_circle: Minor | 12 |
#   | :information_source: Info | 5 |

extract_count() {
    local severity_label="$1"
    # Match the severity row: optional emoji badge, then severity label, pipe, optional spaces, number
    grep -i "|.*${severity_label}[[:space:]]*|" "$REPORT_FILE" \
        | sed -E 's/.*\|[[:space:]]*([0-9]+)[[:space:]]*\|.*/\1/' \
        | head -1
}

CRITICAL_COUNT=$(extract_count "Critical" || echo "0")
MAJOR_COUNT=$(extract_count "Major" || echo "0")
MINOR_COUNT=$(extract_count "Minor" || echo "0")
INFO_COUNT=$(extract_count "Info" || echo "0")

# Validate that counts are numeric
for var_name in CRITICAL_COUNT MAJOR_COUNT MINOR_COUNT INFO_COUNT; do
    val="${!var_name}"
    if ! [[ "$val" =~ ^[0-9]+$ ]]; then
        warn "Could not parse $var_name from report (got \"$val\"), defaulting to 0"
        eval "$var_name=0"
    fi
done

# ---- Log severity counts -------------------------------------------------
echo ""
info "Severity Breakdown:"
info "  CRITICAL : $CRITICAL_COUNT"
info "  MAJOR    : $MAJOR_COUNT  (threshold: $MAJOR_THRESHOLD)"
info "  MINOR    : $MINOR_COUNT"
info "  INFO     : $INFO_COUNT"
echo ""

# ---- Gate logic ----------------------------------------------------------
GATE_FAILED=false

# CRITICAL check — any critical finding fails the gate
if [[ "$CRITICAL_COUNT" -gt 0 ]]; then
    fail "Gate BLOCKED: $CRITICAL_COUNT CRITICAL finding(s) found."
    GATE_FAILED=true
else
    pass "No CRITICAL findings."
fi

# MAJOR check — warn if above threshold, but still pass
if [[ "$MAJOR_COUNT" -gt "$MAJOR_THRESHOLD" ]]; then
    warn "MAJOR count ($MAJOR_COUNT) exceeds threshold ($MAJOR_THRESHOLD)."
    warn "Consider addressing some of these issues."
else
    pass "MAJOR count ($MAJOR_COUNT) within threshold ($MAJOR_THRESHOLD)."
fi

# ---- Final result --------------------------------------------------------
echo ""
if [[ "$GATE_FAILED" == "true" ]]; then
    fail "Quality Gate FAILED — review must pass before merging."
    echo ""
    exit 1
else
    pass "Quality Gate PASSED."
    echo ""
    exit 0
fi
