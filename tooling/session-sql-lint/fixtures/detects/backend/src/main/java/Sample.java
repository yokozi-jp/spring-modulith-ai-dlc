// Distinct package so CodeQL's buildless scan does not see this fixture's
// fully-qualified name clash with the ok/ fixture's Sample. The linter greps
// file contents, so the package has no effect on detection.
package fixtures.detects;

class Sample {
  static final String A = "SELECT pg_try_advisory_lock(1)";
  static final String B = "select pg_advisory_unlock_all()";
  static final String C = "LISTEN orders";
  static final String D = "DECLARE c CURSOR WITH HOLD FOR SELECT 1";
  static final String E = "PREPARE q (int) AS SELECT 1";
  static final String F = "DEALLOCATE q";
  static final String G = "CREATE TEMP TABLE t (id int) ON COMMIT DELETE ROWS";
  static final String H = "LOAD 'auto_explain'";
}
