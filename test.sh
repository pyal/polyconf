#!/bin/bash

YAML="scripts/testArgs.yaml"
verbose=false

usage() {
  echo "Usage: $0 [--verbose|--help]"
  echo "  --verbose  Show full generated command output"
  echo "  --help     Show this help"
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --verbose) verbose=true; shift ;;
    --help)    usage 0 ;;
    *)         usage 1 ;;
  esac
done

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"

echo "Building JAR..."
sbt assembly > /dev/null 2>&1
echo ""

failures=0
total=0

run_test() {
  local desc="$1"
  local gen_cmd_part="$2"
  local setup="$3"

  total=$((total + 1))
  echo "========================================================================"
  echo "  $desc"
  echo "------------------------------------------------------------------------"
  echo "  Generator: $gen_cmd_part"

  gen_output=$(eval "$gen_cmd_part" 2>&1)
  gen_cmd=$(echo "$gen_output" | grep -v '^\[' | grep -v '^$' | tail -1)

  if [ -z "$gen_cmd" ]; then
    echo "  FAIL: no command generated"
    failures=$((failures + 1))
    return
  fi

  echo "  Generated: $gen_cmd"

  [ -n "$setup" ] && eval "$setup"

  if [ "$verbose" = true ]; then
    echo "  Output:"
    bash -c "$gen_cmd -l WARN" 2>&1 | sed 's/^/    /'
    rc=${PIPESTATUS[0]}
  else
    bash -c "$gen_cmd -l WARN" > /dev/null 2>&1
    rc=$?
  fi

  if [ $rc -eq 0 ]; then
    echo "  Result: OK"
  else
    echo "  Result: NOT OK (exit code $rc)"
    failures=$((failures + 1))
  fi
}

run_exec_test() {
  local desc="$1"
  local cmd="$2"
  local setup="$3"

  total=$((total + 1))
  echo "========================================================================"
  echo "  $desc"
  echo "------------------------------------------------------------------------"
  echo "  Command: $cmd"

  [ -n "$setup" ] && eval "$setup"

  if [ "$verbose" = true ]; then
    echo "  Output:"
    eval "$cmd" 2>&1 | sed 's/^/    /'
    rc=${PIPESTATUS[0]}
  else
    eval "$cmd" > /dev/null 2>&1
    rc=$?
  fi

  if [ $rc -eq 0 ]; then
    echo "  Result: OK"
  else
    echo "  Result: NOT OK (exit code $rc)"
    failures=$((failures + 1))
  fi
}

# ============================================================================
# Test cases: description | generator command | setup
# ============================================================================

run_test "shellTest (default)" \
  "./run.sh run.dev.args --yamlPath $YAML::shellTest"

run_test "shellTest (rename /tmp/ to /Users/)" \
  "./run.sh run.dev.args --yamlPath $YAML::shellTest --renameStr \"/tmp/--/Users/\""

run_test "transformerPipeline (default, local env)" \
  "./run.sh run.dev.args --yamlPath $YAML::transformerPipeline" \
  "mkdir -p /tmp/pipeline-output"

run_test "transformerPipeline (ver env)" \
  "./run.sh run.dev.args --yamlPath $YAML::transformerPipeline --renameStr Env--ver" \
  "mkdir -p /tmp/pipeline-verified"

run_test "transformerPipeline (override SCRIPTS_DIR)" \
  "./run.sh run.dev.args --yamlPath $YAML::transformerPipeline --renameStr SCRIPTS_DIR--/tmp/custom" \
  "mkdir -p /tmp/pipeline-output /tmp/custom && cp scripts/input.csv /tmp/custom/input.csv"

run_test "transformerPipeline (from resource instead of file)" \
  "./run.sh run.dev.args --resourcePath testArgs.yaml::transformerPipeline" \
  "mkdir -p /tmp/pipeline-output"

run_exec_test "shellTest (--execute)" \
  "./run.sh run.dev.args --yamlPath $YAML::shellTest --execute"

echo ""
echo "========================================================================"
echo "  $total test(s) | $failures failure(s)"
if [ "$failures" -eq 0 ]; then
  echo "  All OK"
else
  echo "  Some NOT OK"
  exit 1
fi
