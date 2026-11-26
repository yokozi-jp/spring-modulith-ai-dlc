package com.example.demo.eventcontract;

/**
 * {@link EventContract} の規則の違反。
 *
 * @param fixture 違反した fixture のファイル名（ない fixture は、置くべきファイル名）
 * @param problem 何が悪いか
 * @param fix 直し方
 */
/* package */ record ContractViolation(String fixture, String problem, String fix) {

  @Override
  public String toString() {
    return fixture + "：" + problem + " 直し方：" + fix;
  }
}
