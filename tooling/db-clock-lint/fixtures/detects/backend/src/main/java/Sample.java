class Sample {
  static final String A = "SELECT CURRENT_DATE";
  static final String B = "select current_timestamp";
  static final String C = "SELECT now()";
  static final String D = "SELECT sysdate()";
  static final String E = "SELECT date_trunc('day', ordered_at)";
  static final String F = "SELECT ordered_at AT TIME ZONE 'Asia/Tokyo'";
  static final String G = "SELECT ordered_at::date";
  static final String H = "SELECT clock_timestamp()";
  static final String I = "SELECT localtimestamp";
}
