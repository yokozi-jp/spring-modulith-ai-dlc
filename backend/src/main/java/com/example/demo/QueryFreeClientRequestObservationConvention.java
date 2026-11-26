package com.example.demo;

import io.micrometer.common.KeyValue;
import java.util.regex.Pattern;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.stereotype.Component;

/**
 * 送信の HTTP の observation の {@code http.url} と {@code uri} から、クエリと fragment を落とす（ADR-140）。
 *
 * <p>Spring の既定は {@code ?api_key=...} のような部分をそのまま入れ、span と {@code http.client.requests} の tag に載せる。
 * Boot の {@code RestClient.Builder} と {@code RestTemplateBuilder} が、この Bean を使う。 Bean があると {@code
 * management.observations.http.client.requests.name} は効かず、名前は既定の {@code http.client.requests} になる。
 */
@Component
final class QueryFreeClientRequestObservationConvention
    extends DefaultClientRequestObservationConvention {

  /** 最初の {@code ?} か {@code #} から末尾まで。改行を含む値でも落とす。 */
  private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("(?s)[?#].*");

  @Override
  protected KeyValue uri(final ClientRequestObservationContext context) {
    return withoutQuery(super.uri(context));
  }

  @Override
  protected KeyValue requestUri(final ClientRequestObservationContext context) {
    return withoutQuery(super.requestUri(context));
  }

  private static KeyValue withoutQuery(final KeyValue keyValue) {
    return KeyValue.of(
        keyValue.getKey(), QUERY_OR_FRAGMENT.matcher(keyValue.getValue()).replaceFirst(""));
  }
}
