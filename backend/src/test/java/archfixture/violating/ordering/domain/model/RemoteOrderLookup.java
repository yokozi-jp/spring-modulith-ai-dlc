package archfixture.violating.ordering.domain.model;

import java.net.http.HttpClient;

/** 違反：domainModelDependsOnlyOnAllowedTypes（domain.model が java.net.http の HttpClient を使う）。 */
public final class RemoteOrderLookup {

  /** 外部の注文システムを呼ぶクライアント。 */
  private final HttpClient client;

  /** 外部の注文システムを呼ぶクライアントを受け取る。 */
  public RemoteOrderLookup(final HttpClient client) {
    this.client = client;
  }

  /** 使っているクライアントを返す。 */
  public HttpClient current() {
    return client;
  }
}
