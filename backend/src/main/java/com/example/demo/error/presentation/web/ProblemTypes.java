package com.example.demo.error.presentation.web;

import com.example.demo.DemoApplication;
import com.example.demo.shared.failure.FailureReason;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * problem type と title のキーを、{@link CommonProblem} とモジュールの {@link FailureReason} から作る（ADR-127）。
 *
 * <p>type は {@code /problems/<パス>}、title のキーは {@code problem.title.<パス>} で、同じパスを使う。 パスは、{@code
 * CommonProblem} では定数の名前の kebab-case、{@code FailureReason} では {@code <module>/<定数の名前の kebab-case>}
 * である。
 */
final class ProblemTypes {

  /** RFC 9457 が一般的な HTTP エラーに定義する problem type。 */
  /* package */ static final URI ABOUT_BLANK = URI.create("about:blank");

  /** problem の title の MessageSource のキーの接頭辞。status の title（{@code problem.title.<status>}）も使う。 */
  /* package */ static final String TITLE_PREFIX = "problem.title.";

  /** 業務固有の problem type の接頭辞。パスを全部書いた相対 URI にする（ADR-058、RFC 9457 §3.1.1）。 */
  private static final String TYPE_PREFIX = "/problems/";

  private ProblemTypes() {}

  /** モジュールによらない problem のパスを返す。 */
  /* package */ static String path(final CommonProblem problem) {
    return kebabCase(problem.name());
  }

  /** 業務規則の違反の理由のパス {@code <module>/<kebab-case>} を返す。 */
  /* package */ static String path(final FailureReason reason) {
    return reason.module() + "/" + kebabCase(reason.name());
  }

  /** パスの problem type を返す。 */
  /* package */ static URI type(final String path) {
    return URI.create(TYPE_PREFIX + path);
  }

  /** パスの title のキーを返す。 */
  /* package */ static String titleKey(final String path) {
    return TITLE_PREFIX + path;
  }

  /**
   * 応答が返しうる problem type を返す。OpenAPI の {@code ProblemDetail.type} に載せる。
   *
   * <p>about:blank の後に、{@link #paths()} の順で並べる。
   */
  /* package */ static List<String> all() {
    return Stream.concat(Stream.of(ABOUT_BLANK), paths().map(ProblemTypes::type))
        .map(URI::toString)
        .toList();
  }

  /** 業務固有の problem のパスを、{@link CommonProblem} の宣言順、ベースパッケージの下の理由の辞書順に返す。 */
  /* package */ static Stream<String> paths() {
    return Stream.concat(
        Arrays.stream(CommonProblem.values()).map(ProblemTypes::path),
        reasonsUnder(DemoApplication.class.getPackageName()).stream()
            .map(ProblemTypes::path)
            .sorted());
  }

  /**
   * basePackage の下で {@link FailureReason} を実装する enum の定数を、クラスパスから走査して返す。
   *
   * <p>テストの実行時はテストのクラスも走査する。テストの enum はベースパッケージの外に置く。
   */
  /* package */ static List<FailureReason> reasonsUnder(final String basePackage) {
    final ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          // 既定の判定は抽象クラスを除き、定数ごとの本体で抽象メソッドを実装する enum を落とす。入れ子でないことだけを見る。
          @Override
          protected boolean isCandidateComponent(final AnnotatedBeanDefinition beanDefinition) {
            return beanDefinition.getMetadata().isIndependent();
          }
        };
    scanner.addIncludeFilter(new AssignableTypeFilter(FailureReason.class));
    return scanner.findCandidateComponents(basePackage).stream()
        .map(BeanDefinition::getBeanClassName)
        .map(name -> ClassUtils.resolveClassName(Objects.requireNonNull(name), null))
        // FailureReason 自身も候補になる。実装を enum に限ることは ArchUnit が確かめる。
        .filter(Class::isEnum)
        .flatMap(type -> Arrays.stream(type.getEnumConstants()))
        .map(FailureReason.class::cast)
        .toList();
  }

  private static String kebabCase(final String constant) {
    return constant.toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
