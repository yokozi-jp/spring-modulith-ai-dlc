package com.example.demo.testkit;

import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;

/** commit した OpenAPI の契約を swagger-core で読み、operation を突き合わせるキーを作る。契約を読むテストで共有する。 */
public final class OpenApiOperations {

  /** commit した OpenAPI の契約。テストは backend を作業ディレクトリにして走る。 */
  public static final Path OPENAPI = Path.of("..", "openapi", "openapi.yaml");

  /** query の引数のうち id とみなす名前。Spectral の {@code public-id-uuid}（.spectral.yaml）と同じ選び方である。 */
  private static final Pattern QUERY_ID = Pattern.compile("(^id|Id)$");

  private OpenApiOperations() {}

  /** commit した OpenAPI の契約を読む。 */
  public static OpenAPI load() {
    try {
      return Yaml31.mapper().readValue(OPENAPI.toFile(), OpenAPI.class);
    } catch (IOException e) {
      throw new UncheckedIOException("OpenAPI の契約を読めない: " + OPENAPI, e);
    }
  }

  /** commit した OpenAPI の契約の operation を返す。 */
  public static List<SpecOperation> operations() {
    return load().getPaths().entrySet().stream()
        .flatMap(path ->
            path.getValue().readOperationsMap().entrySet().stream()
                .map(operation ->
                    new SpecOperation(
                        HttpMethod.valueOf(operation.getKey().name()),
                        path.getKey(),
                        Stream.of(
                                path.getValue().getParameters(),
                                operation.getValue().getParameters())
                            .filter(Objects::nonNull)
                            .flatMap(List::stream)
                            .anyMatch(OpenApiOperations::isId),
                        List.copyOf(operation.getValue().getResponses().keySet()))))
        .toList();
  }

  /** operation を突き合わせるキー（「メソッド パス」）を返す。 */
  public static String key(final String method, final String path) {
    return method + " " + path;
  }

  private static boolean isId(final Parameter parameter) {
    return "path".equals(parameter.getIn())
        || ("query".equals(parameter.getIn()) && QUERY_ID.matcher(parameter.getName()).find());
  }

  /**
   * 契約の 1 つの operation。
   *
   * @param method HTTP メソッド
   * @param path パスのテンプレート
   * @param hasId パスの引数か、名前が {@code id} か {@code Id} で終わる query の引数を持つか
   * @param statuses 応答の status（契約に書いたとおりの文字列）
   */
  public record SpecOperation(
      HttpMethod method, String path, boolean hasId, List<String> statuses) {

    /** status を変更できないリストで持つ。 */
    public SpecOperation {
      statuses = List.copyOf(statuses);
    }

    /** {@link OpenApiOperations#key} で作るキー。 */
    public String key() {
      return OpenApiOperations.key(method.name(), path);
    }
  }
}
