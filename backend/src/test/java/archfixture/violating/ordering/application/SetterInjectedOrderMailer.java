package archfixture.violating.ordering.application;

import org.springframework.beans.factory.annotation.Autowired;

/** 違反：autowiredMethodsAreNotUsed（メソッドに {@code @Autowired} を付けて依存を受け取る）。 */
public class SetterInjectedOrderMailer {

  /** メールの送信元のアドレス。 */
  private String sender = "";

  /** メールの送信元のアドレスをメソッドインジェクションで受け取る。 */
  @Autowired
  public void setSender(final String sender) {
    this.sender = sender;
  }

  /** メールの送信元のアドレスを返す。 */
  public String currentSender() {
    return sender;
  }
}
