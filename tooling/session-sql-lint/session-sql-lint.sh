#!/usr/bin/env bash
# session-sql-lint.sh: fails if the backend production code under <root> uses SQL that keeps session state
# (session advisory locks, LISTEN, WITH HOLD, PREPARE, DEALLOCATE, ON COMMIT PRESERVE/DELETE ROWS, LOAD).
# Such SQL breaks behind connection-multiplexing proxies (PgBouncer transaction mode, RDS Proxy).
# Run: bash tooling/session-sql-lint/session-sql-lint.sh <root> (task lint-session-sql runs it with the repository root)

set -u

root="${1:?usage: session-sql-lint.sh <root>}"
pattern='\bpg_(try_)?advisory_(lock|unlock|unlock_all)(_shared)?\b|(^|[^a-z0-9_])listen[[:space:]]+[a-z_"]|\bwith[[:space:]]+hold\b|\bprepare[[:space:]]+[a-z_"][a-z0-9_"]*[[:space:]]*(\([^)]*\))?[[:space:]]*as\b|\bdeallocate\b|\bon[[:space:]]+commit[[:space:]]+(preserve|delete)[[:space:]]+rows\b|\bload[[:space:]]+'"'"

# Javadoc, line comments, and YAML and SQL comment lines are excluded.
found="$({ grep -rnEiI --include='*.java' "$pattern" "$root/backend/src/main/java" || true
  grep -rnEiI --include='*.sql' --include='*.yaml' --include='*.yml' "$pattern" "$root/backend/src/main/resources/db" || true; } \
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*|#|--)' || true)"
if [ -n "$found" ]; then
  printf '%s\n' "$found" >&2
  echo "✗ 接続を多重化するプロキシ（PgBouncer のトランザクションモード、RDS Proxy）の後ろで壊れる、セッションに状態を残す SQL があります。" >&2
  echo "  トランザクションの中で閉じる方式（pg_try_advisory_xact_lock、ON COMMIT DROP など）か、DB の表のロック（ShedLock）に替えてください。" >&2
  echo "  規約: docs/principles/right-sizing.md、docs/tooling/lint-and-test.md" >&2
  exit 1
fi
echo "session-sql-lint OK"
