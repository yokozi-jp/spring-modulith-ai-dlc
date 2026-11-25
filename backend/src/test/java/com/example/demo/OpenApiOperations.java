package com.example.demo;

import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** commit した OpenAPI の契約の operation を、swagger-core で読む（{@code ApiContractCoverageTest} と同じ読み方）。 */
final class OpenApiOperations {

  /** commit した OpenAPI の契約。テストは backend を作業ディレクトリにして走る。 */
  /* package */ static final Path OPENAPI = Path.of("..", "openapi", "openapi.yaml");

  /** query の引数のうち id とみなす名前。Spectral の {@code public-id-uuid}（.spectral.yaml）と同じ選び方である。 */
  private static final Pattern QUERY_ID = Pattern.compile("(^id|Id)$");

  private OpenApiOperations() {}

  /**
   * operation ごとに、パスの引数か、名前が {@code id} か {@code Id} で終わる query の引数を持つかを返す。
   *
   * @return キーは「メソッド パス」
   */
  /* package */ static Map<String, Boolean> withIdParameter() {
    final OpenAPI openApi;
    try {
      openApi = Yaml31.mapper().readValue(OPENAPI.toFile(), OpenAPI.class);
    } catch (IOException e) {
      throw new IllegalStateException("OpenAPI の契約を読めない: " + OPENAPI, e);
    }
    return openApi.getPaths().entrySet().stream()
        .flatMap(path ->
            path.getValue().readOperationsMap().entrySet().stream()
                .map(operation ->
                    Map.entry(
                        operation.getKey().name() + " " + path.getKey(),
                        Stream.of(
                                path.getValue().getParameters(),
                                operation.getValue().getParameters())
                            .filter(Objects::nonNull)
                            .flatMap(List::stream)
                            .anyMatch(OpenApiOperations::isId))))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static boolean isId(final Parameter parameter) {
    return "path".equals(parameter.getIn())
        || ("query".equals(parameter.getIn()) && QUERY_ID.matcher(parameter.getName()).find());
  }
}
