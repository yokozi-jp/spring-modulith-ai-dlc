package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersAndListenersDoNotUseInheritance（{@link OrderHandlerSupport} を継承し、受け継いだ handle に
 * pgm_cd が束縛されない）。
 */
public class RestoreOrderCommandHandler extends OrderHandlerSupport {

  /** 復元の理由を返す。 */
  public String reason() {
    return "restore";
  }
}
