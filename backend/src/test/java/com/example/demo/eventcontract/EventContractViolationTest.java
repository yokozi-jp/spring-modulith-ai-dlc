package com.example.demo.eventcontract;

import static org.assertj.core.api.Assertions.assertThat;

import archfixture.eventcontract.CompatibleEvent;
import archfixture.eventcontract.ComponentRemoved;
import archfixture.eventcontract.ComponentRenamed;
import archfixture.eventcontract.ComponentTypeChanged;
import archfixture.eventcontract.NestedRequiredComponentAdded;
import archfixture.eventcontract.NullableComponentAdded;
import archfixture.eventcontract.PrimitiveAdded;
import archfixture.eventcontract.RequiredComponentAdded;
import archfixture.eventcontract.VersionGap;
import archfixture.eventcontract.WithoutFixture;
import com.example.demo.eventcontract.EventContract.Violation;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.modulith.events.core.EventSerializer;

/**
 * {@link EventContract#check} が、わざと違反する {@code archfixture.eventcontract} の型と {@code
 * archfixture-events/<シナリオ>/} の fixture の違反を、fixture を挙げて検出することを確かめる。
 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class EventContractViolationTest {

  /** 違反例の型のパッケージ。fixture のファイル名の接頭辞に使う。 */
  private static final String PACKAGE = "archfixture.eventcontract.";

  /** 読んで書き直した JSON が fixture と違うときの問題の書き出し。 */
  private static final String REWRITTEN_DIFFERS = "読んで書き直した JSON が fixture と違う";

  @ParameterizedTest(name = "{0} は {2} を「{3}」で挙げる")
  @MethodSource("violations")
  @DisplayName("違反する型と fixture を、fixture と問題を挙げて検出する")
  void detectsViolation(
      final String scenario,
      final Set<Class<?>> listenedEventTypes,
      final String fixture,
      final String problem) {
    EventContract.SERIALIZER_CONTEXT.run(context ->
        assertThat(check(scenario, listenedEventTypes, context.getBean(EventSerializer.class)))
            .as("シナリオ %s の違反", scenario)
            .anySatisfy(violation -> {
              assertThat(violation.fixture()).isEqualTo(fixture);
              assertThat(violation.problem()).contains(problem);
              assertThat(violation.fix()).isNotBlank();
            }));
  }

  @Test
  @DisplayName("@Nullable の component を足し、今の形の fixture を次の版で足した型は違反しない")
  void acceptsNullableComponentWithNewestFixture() {
    EventContract.SERIALIZER_CONTEXT.run(context ->
        assertThat(
                check(
                    "compatible",
                    Set.of(CompatibleEvent.class),
                    context.getBean(EventSerializer.class)))
            .as("シナリオ compatible の違反")
            .isEmpty());
  }

  /** シナリオ、リスナーが受ける型、挙げる fixture、問題に含む文言。 */
  private static Stream<Arguments> violations() {
    return Stream.of(
        Arguments.of(
            "component-removed",
            Set.of(ComponentRemoved.class),
            PACKAGE + "ComponentRemoved.v1.json",
            REWRITTEN_DIFFERS),
        Arguments.of(
            "component-renamed",
            Set.of(ComponentRenamed.class),
            PACKAGE + "ComponentRenamed.v1.json",
            REWRITTEN_DIFFERS),
        Arguments.of(
            "component-renamed",
            Set.of(ComponentRenamed.class),
            PACKAGE + "ComponentRenamed.v1.json",
            "@Nullable でない component orderNumber が null"),
        Arguments.of(
            "component-type-changed",
            Set.of(ComponentTypeChanged.class),
            PACKAGE + "ComponentTypeChanged.v1.json",
            "EventSerializer で " + ComponentTypeChanged.class.getName() + " に読めない"),
        Arguments.of(
            "required-component-added",
            Set.of(RequiredComponentAdded.class),
            PACKAGE + "RequiredComponentAdded.v1.json",
            "@Nullable でない component note が null"),
        Arguments.of(
            "nested-required-component-added",
            Set.of(NestedRequiredComponentAdded.class),
            PACKAGE + "NestedRequiredComponentAdded.v1.json",
            "@Nullable でない component item.note が null"),
        Arguments.of(
            "primitive-added",
            Set.of(PrimitiveAdded.class),
            PACKAGE + "PrimitiveAdded.v1.json",
            REWRITTEN_DIFFERS),
        Arguments.of(
            "type-renamed",
            Set.of(),
            PACKAGE + "RenamedAway.v1.json",
            "fixture の型 " + PACKAGE + "RenamedAway を読み込めない"),
        Arguments.of(
            "newest-fixture-missing",
            Set.of(NullableComponentAdded.class),
            PACKAGE + "NullableComponentAdded.v1.json",
            "最も新しい版の fixture を読んだ値の component note が null"),
        Arguments.of(
            "no-fixture",
            Set.of(WithoutFixture.class),
            PACKAGE + "WithoutFixture.v1.json",
            "の v1 の fixture がない"),
        Arguments.of(
            "version-gap",
            Set.of(VersionGap.class),
            PACKAGE + "VersionGap.v2.json",
            "の v2 の fixture がない"),
        Arguments.of("no-listener", Set.of(), PACKAGE + "WithoutListener.v1.json", "を受けるリスナーがない"),
        Arguments.of("bad-file-name", Set.of(), PACKAGE + "WithoutListener.v01.json", "の形でない"),
        Arguments.of("bad-file-name", Set.of(), PACKAGE + "WithoutListener.v0.json", "の形でない"),
        Arguments.of("bad-file-name", Set.of(), PACKAGE + "WithoutListener.json", "の形でない"));
  }

  private static List<Violation> check(
      final String scenario,
      final Set<Class<?>> listenedEventTypes,
      final EventSerializer serializer) {
    return EventContract.checkDirectory(
        listenedEventTypes, "archfixture-events/" + scenario, serializer);
  }
}
