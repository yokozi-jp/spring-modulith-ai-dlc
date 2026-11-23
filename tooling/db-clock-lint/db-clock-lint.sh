#!/usr/bin/env bash
# db-clock-lint.sh: fails if the backend production code under <root> reads the DB clock or builds calendar dates
# from timestamps in SQL (CURRENT_DATE, now(), date_trunc, ::date, AT TIME ZONE and the like).
# These depend on the session TimeZone or bypass the injected Clock (docs/datetime/timezone-conventions.md).
# Run: bash tooling/db-clock-lint/db-clock-lint.sh <root> (task lint-db-clock runs it with the repository root)

set -u

root="${1:?usage: db-clock-lint.sh <root>}"
pattern='\bcurrent_(date|time|timestamp)\b|\blocaltime(stamp)?\b|\bnow[[:space:]]*\(\)|\bsysdate\b|\b(clock|statement|transaction)_timestamp[[:space:]]*\(|\bdate_trunc[[:space:]]*\(|\bat[[:space:]]+time[[:space:]]+zone\b|::[[:space:]]*date\b'

# Javadoc, line comments, and YAML and SQL comment lines are excluded.
found="$({ grep -rnEiI --include='*.java' "$pattern" "$root/backend/src/main/java" || true
  grep -rnEiI --include='*.sql' --include='*.yaml' --include='*.yml' "$pattern" "$root/backend/src/main/resources/db" || true; } \
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*|#|--)' || true)"
if [ -n "$found" ]; then
  printf '%s\n' "$found" >&2
  echo "✗ DB の時計を読むか、日時から暦日や境界を SQL で作る文があります。" >&2
  echo "  現在時刻はアプリの Clock から求め、暦日と期間の境界は業務タイムゾーンで求めて引数で渡してください。" >&2
  echo "  規約: docs/datetime/timezone-conventions.md、docs/tooling/lint-and-test.md" >&2
  exit 1
fi
echo "db-clock-lint OK"
