package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersAndListenersDoNotUseInheritance（役割名でない名前で {@link ReopenOrderCommandHandler}
 * を継承し、継承した handle に pgm_cd が束縛されない）。
 */
public class ReopenOrderHandler extends ReopenOrderCommandHandler {

  /** 再開の理由を返す。 */
  public String reason() {
    return "reopen";
  }
}
