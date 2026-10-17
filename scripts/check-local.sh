#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RUN_ANDROID=0
RUN_IOS=0
SKIP_SECURITY=0
SKIP_AUDIT=0
for arg in "$@"; do
  case "$arg" in
    --android) RUN_ANDROID=1 ;;
    --ios) RUN_IOS=1 ;;
    --skip-security) SKIP_SECURITY=1 ;;
    --skip-dependency-audit) SKIP_AUDIT=1 ;;
    *) printf 'Unknown option: %s\nUsage: %s [--android] [--ios] [--skip-security] [--skip-dependency-audit]\n' "$arg" "$0" >&2; exit 2 ;;
  esac
done

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'ERROR: required tool not found on PATH: %s\n' "$1" >&2
    exit 2
  fi
}

need node
need npm
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 11)) { console.error(`Node 22.11+ required; found ${process.version}`); process.exit(1); }'

printf '\n== Publication gate ==\n'
python3 scripts/check-publication.py --dry-run

printf '\n== Locked JavaScript dependencies ==\n'
npm ci

printf '\n== TypeScript ==\n'
npm run typecheck

printf '\n== JavaScript syntax check (not a style linter) ==\n'
while IFS= read -r -d '' file; do
  node --check "$file"
done < <(find . -maxdepth 2 -type f \( -name '*.js' -o -name '*.cjs' \) -not -path './node_modules/*' -print0)
find src scripts -type f \( -name '*.js' -o -name '*.cjs' \) -print0 2>/dev/null | while IFS= read -r -d '' file; do node --check "$file"; done

printf '\n== Jest with configured coverage thresholds ==\n'
npm test -- --runInBand

if (( SKIP_AUDIT )); then
  printf 'SKIPPED: npm audit (--skip-dependency-audit)\n'
else
  printf '\n== Dependency vulnerability audit ==\n'
  npm audit --audit-level=high
fi

if (( SKIP_SECURITY )); then
  printf 'SKIPPED: Gitleaks and zizmor (--skip-security)\n'
else
  need gitleaks
  need zizmor
  printf '\n== Git history secret scan ==\n'
  gitleaks git . --redact --no-banner
  printf '\n== Staged changes secret scan ==\n'
  gitleaks git --staged . --redact --no-banner
  printf '\n== GitHub Actions workflow scan ==\n'
  zizmor --persona auditor --strict-collection .github/workflows
fi

if (( RUN_ANDROID )); then
  need java
  [[ -x ./gradlew ]] || { printf 'ERROR: Gradle wrapper is missing or not executable\n' >&2; exit 2; }
  JAVA_VERSION="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
  [[ "$JAVA_VERSION" == 17.* ]] || { printf 'ERROR: Android checks require JDK 17; found %s\n' "${JAVA_VERSION:-unknown}" >&2; exit 2; }
  if [[ -n "${JAVA_HOME:-}" ]]; then
    ANDROID_JAVA_HOME="$JAVA_HOME"
  elif [[ "$(uname -s)" == "Darwin" ]]; then
    ANDROID_JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  else
    ANDROID_JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  fi
  printf '\n== Android release/R8 and strict native coverage ==\n'
  ./gradlew -Dorg.gradle.java.home="$ANDROID_JAVA_HOME" :app:nativeCoverageCheck :app:assembleRelease
else
  printf 'SKIPPED: Android native coverage and release/R8 build (pass --android)\n'
fi

if (( RUN_IOS )); then
  [[ "$(uname -s)" == "Darwin" ]] || { printf 'ERROR: iOS build requires macOS/Xcode\n' >&2; exit 2; }
  XCODE_APP="${XCODE_APP:-/Applications/Xcode_26.3.app}"
  [[ -d "$XCODE_APP" ]] || { printf 'ERROR: pinned Xcode app not found: %s\n' "$XCODE_APP" >&2; exit 2; }
  export DEVELOPER_DIR="$XCODE_APP/Contents/Developer"
  xcodebuild -version
  printf '\n== iOS unsigned Release build ==\n'
  need bundle
  bundle install --jobs 4 --retry 3
  bundle exec pod install --project-directory=ios
  xcodebuild -workspace ios/Cutout.xcworkspace -scheme Cutout -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build
else
  printf 'SKIPPED: iOS unsigned Release build (pass --ios on macOS)\n'
fi

printf '\nAll selected checks passed. Any SKIPPED checks above were not run.\n'
