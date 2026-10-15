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
