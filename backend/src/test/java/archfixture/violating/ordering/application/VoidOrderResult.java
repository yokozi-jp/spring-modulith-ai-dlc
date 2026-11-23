package archfixture.violating.ordering.application;

/** 違反：commandsAndResultsAreApplicationRecords（sealed interface の許可された型に class を持つ）。 */
public sealed interface VoidOrderResult permits VoidOrderResult.Voided {

  /** 許可された型が record でない。 */
  final class Voided implements VoidOrderResult {}
}
