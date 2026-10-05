#!/usr/bin/env bash
# Runs one shard of the backend test suite (docs/ci.md). The full suite takes longer than one comfortable CI
# job, so it is split; shard "b" is defined by exclusion, so every test class belongs to exactly one shard
# and a new test can never fall between them.
#
#   scripts/ci/backend-tests.sh a       # accounts, businesses, billing, security, operations
#   scripts/ci/backend-tests.sh b       # everything else except the Cube stack tests
#   scripts/ci/backend-tests.sh cube    # Cube reports and Cube purge (Cube + Cube Store containers)
#   scripts/ci/backend-tests.sh all     # the whole suite in one run
set -euo pipefail

SHARD_A_PACKAGES=(account accountdata audit billing business mail ops retention security tenancy)
CUBE_CLASSES=(CubeReportsIntegrationTest CubePurgeIntegrationTest)

join() { local IFS=,; echo "$*"; }

a_patterns=()
not_a_patterns=()
for package in "${SHARD_A_PACKAGES[@]}"; do
  a_patterns+=("com/oussamaksantini/insightstudio/${package}/**")
  not_a_patterns+=("!com/oussamaksantini/insightstudio/${package}/**")
done
not_cube=()
for class in "${CUBE_CLASSES[@]}"; do not_cube+=("!${class}"); done

case "${1:-}" in
  a)    tests=$(join "${a_patterns[@]}" "${not_cube[@]}") ;;
  b)    tests=$(join "${not_a_patterns[@]}" "${not_cube[@]}") ;;
  cube) tests=$(join "${CUBE_CLASSES[@]}") ;;
  all)  tests="" ;;
  *)    echo "usage: $0 a|b|cube|all" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/../../apps/api"
args=(-B -ntp verify -Dsurefire.failIfNoSpecifiedTests=false)
if [[ -n "$tests" ]]; then
  args+=("-Dtest=${tests}")
fi
if [[ "${1}" == "cube" ]]; then
  # The Cube stack has known intermittent failures (docs/release-checks.md): a failed test is run once more and
  # reported as flaky in the surefire report instead of being hidden or failing the build on a single blip.
  args+=(-Dsurefire.rerunFailingTestsCount=1)
fi
echo "mvnw ${args[*]}"
exec ./mvnw "${args[@]}"
