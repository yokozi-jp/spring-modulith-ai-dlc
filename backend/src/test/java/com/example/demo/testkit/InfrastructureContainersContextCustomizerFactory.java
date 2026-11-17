package com.example.demo.testkit;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Spring のテストのコンテキストへ、{@link InfrastructureContainers} の接続先を最も優先する設定として足す（ADR-085）。
 *
 * <p>{@code META-INF/spring.factories} で全テストに登録する。 接続先は {@code application.yaml}
 * の環境変数の名前で渡すため、{@code spring.datasource.url} などを読むテストも、起動したコンテナを指す。
 * コンテナは、コンテキストを作るときに初めて起動するため、Spring を使わないテストだけの fork では起動しない。
 */
public final class InfrastructureContainersContextCustomizerFactory
    implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      final Class<?> testClass, final List<ContextConfigurationAttributes> configAttributes) {
    return new Customizer();
  }

  /** すべてのテストで等しく、コンテキストのキャッシュの鍵を分けない。 */
  private record Customizer() implements ContextCustomizer {

    @Override
    public void customizeContext(
        final ConfigurableApplicationContext context,
        final MergedContextConfiguration mergedConfig) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource("testInfrastructure", InfrastructureContainers.properties()));
    }
  }
}
