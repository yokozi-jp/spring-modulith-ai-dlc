package com.example.demo.eventcontract;

import com.example.demo.eventcontract.EventContract.EventFixture;
import com.example.demo.eventcontract.EventContract.Violation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** クラスパスのディレクトリから fixture を読み、ファイル名 {@code <型の完全な名前>.v<n>.json} を解釈する。 */
final class EventFixtures {

  /** fixture のファイル名。版は 1 から数え、先頭に 0 を付けない。 */
  private static final Pattern FILE_NAME = Pattern.compile("(.+)\\.v([1-9][0-9]*)\\.json");

  private EventFixtures() {}

  /**
   * 読んだ fixture と、名前の形が違うファイルの違反。
   *
   * @param fixtures 名前を解釈できた fixture
   * @param violations 名前の形が違うファイルの違反
   */
  /* package */ record Loaded(List<EventFixture> fixtures, List<Violation> violations) {}

  /**
   * クラスパスのディレクトリの直下のファイルを読む。
   *
   * @param directory クラスパスのディレクトリ（{@code events} など）
   * @return 読んだ fixture と、名前の形が違うファイルの違反
   */
  /* package */ static Loaded load(final String directory) {
    final List<EventFixture> fixtures = new ArrayList<>();
    final List<Violation> violations = new ArrayList<>();
    for (final Resource resource : resources(directory)) {
      final String fileName = Objects.requireNonNull(resource.getFilename(), "fileName");
      final Matcher matcher = FILE_NAME.matcher(fileName);
      if (matcher.matches()) {
        fixtures.add(
            new EventFixture(
                matcher.group(1), Integer.parseInt(matcher.group(2)), read(resource), fileName));
      } else {
        violations.add(
            new Violation(
                fileName,
                "ファイル名が <型の完全な名前>.v<n>.json の形でない。",
                "ファイル名を <型の完全な名前>.v<n>.json にする。n は 1 から数え、先頭に 0 を付けない。"));
      }
    }
    return new Loaded(fixtures, violations);
  }

  private static List<Resource> resources(final String directory) {
    try {
      return List.of(
              new PathMatchingResourcePatternResolver()
                  .getResources("classpath*:" + directory + "/*"))
          .stream()
          .filter(Resource::isReadable)
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("fixture のディレクトリを読めない：" + directory, e);
    }
  }

  private static String read(final Resource resource) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("fixture を読めない：" + resource, e);
    }
  }
}
