class Sample {
  // LISTEN x
  /*
   * pg_advisory_lock
   */
  static final String A = "SELECT pg_try_advisory_xact_lock(1)";
  static final String B = "SELECT pg_advisory_xact_lock(1)";
  static final String C = "EVENT_PUBLICATION.LISTENER_ID";
  static final String D = "connection.prepareStatement(sql)";
  static final String E = "CREATE TEMP TABLE t (id int) ON COMMIT DROP";
  static final String F = "loadFactories()";
}
