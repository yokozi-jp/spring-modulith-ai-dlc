package archfixture.violating.ordering.application;

import org.springframework.stereotype.Service;

/** 違反：publicBeanMethodsAreNotFinal（public な Bean の public メソッドを final にする）。 */
@Service
public class OrderReporter {

  /** 注文の件数の見出しを返す。 */
  public final String report() {
    return "orders";
  }

  /** 注文の件数の見出しの接頭辞を返す。final でないので、規則に違反しない。 */
  public String prefix() {
    return "orders:";
  }
}
