package com.example.demo.eventcontract;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 保存したイベント出版を、デプロイの後のイベントの型で読めるかの規則。
 *
 * <p>Spring Modulith は、イベントを {@link EventSerializer} で JSON にして {@code
 * modulith.event_publication.serialized_event} に保存し、再投入のときにデプロイの後のコードの型で読む。過去の形の JSON を fixture
 * として残し、今の型で読めることを確かめる。全件のテストと違反例のテストが {@link #check} を呼ぶ。
 */
final class EventContract {

  /**
   * 本番と同じ {@link EventSerializer} を持つ文脈。
   *
   * <p>{@code application.yaml} を読み、Spring Boot の Jackson の自動構成と、Spring Modulith の Jackson 3 の
   * {@code EventSerializer} の自動構成だけを載せる。後者は package-private のため、名前で読み込む。
   *
   * <p>Spring Modulith の版を上げると、{@code JacksonEventSerializationConfiguration}
   * の名前かパッケージが変わるか、外から見えなくなることがある。この定数がクラスの初期化で例外を投げたら、新しい版の {@code spring-modulith-events-jackson}
   * で {@code EventSerializer} を登録する自動構成のクラスを探し、ここの名前をそれに直す。
   */
  /* package */ static final ApplicationContextRunner SERIALIZER_CONTEXT =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  ClassUtils.resolveClassName(
                      "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration",
                      null)));

  /** テストのリソースの根。直し方に fixture の置き場所を書くのに使う。 */
  private static final String RESOURCE_ROOT = "backend/src/test/resources/";

  /** 変更の規則に反したときの直し方。 */
  private static final String FOLLOW_CHANGE_RULE =
      "docs/backend/class-roles/event.md の「必須の記述」の変更の規則に従う。変更を戻すか、新しい型のイベントを足す。";

  /** JSON の木の比較だけに使う、設定を持たない mapper。 */
  private static final JsonMapper TREE = JsonMapper.shared();

  private EventContract() {}

  /** {@link EventSerializer} で読めた fixture と、読んだ値。 */
  private record ReadFixture(EventFixture fixture, Object value) {}

  /** 読んだ値で {@code null} だった component。 */
  private record NullComponent(String path, boolean nullable) {}

  /**
   * クラスパスのディレクトリの fixture を読み、リスナーが受ける型と照らして違反を返す。
   *
   * @param listenedEventTypes {@code @ApplicationModuleListener} が受けるイベントの型
   * @param fixtureDirectory fixture を置くクラスパスのディレクトリ（{@code events} など）
   * @param serializer 本番と同じ {@link EventSerializer}
   * @return 違反。ない場合は空
   */
  /* package */ static List<ContractViolation> check(
      final Set<Class<?>> listenedEventTypes,
      final String fixtureDirectory,
      final EventSerializer serializer) {
    final EventFixtures.Loaded loaded = EventFixtures.load(fixtureDirectory);
    final Map<String, Class<?>> listened =
        listenedEventTypes.stream()
            .collect(toMap(Class::getName, Function.identity(), (a, b) -> a, TreeMap::new));
    final Map<String, List<EventFixture>> byType =
        loaded.fixtures().stream()
            .sorted(Comparator.comparingInt(EventFixture::version))
            .collect(groupingBy(EventFixture::typeName, TreeMap::new, toList()));
    final List<ContractViolation> violations = new ArrayList<>(loaded.violations());
    listened
        .keySet()
        .forEach(typeName ->
            violations.addAll(
                missingVersions(
                    typeName, byType.getOrDefault(typeName, List.of()), fixtureDirectory)));
    byType.forEach((typeName, fixtures) -> {
      final Class<?> type = listened.get(typeName);
      violations.addAll(
          type == null
              ? unresolvedType(fixtures)
              : readableFixtures(type, fixtures, fixtureDirectory, serializer));
    });
    return violations;
  }

  /** リスナーが受ける型の、v1 から最も新しい版までで欠けた版。版の昇順に並んだ fixture を受ける。 */
  private static List<ContractViolation> missingVersions(
      final String typeName, final List<EventFixture> fixtures, final String fixtureDirectory) {
    final Set<Integer> present = fixtures.stream().map(EventFixture::version).collect(toSet());
    final int newest = fixtures.isEmpty() ? 1 : fixtures.getLast().version();
    return IntStream.rangeClosed(1, newest)
        .filter(version -> !present.contains(version))
        .mapToObj(version -> {
          final String fileName = EventFixture.fileName(typeName, version);
          return new ContractViolation(
              fileName,
              "リスナーが受ける型 " + typeName + " の v" + version + " の fixture がない。版は v1 から欠けずに続ける。",
              "fixture を " + RESOURCE_ROOT + fixtureDirectory + "/" + fileName + " に置く。");
        })
        .toList();
  }

  /** リスナーが受けない型の fixture。型を読み込めるならリスナーがなく、読み込めないなら型の名前かパッケージが変わった。 */
  private static List<ContractViolation> unresolvedType(final List<EventFixture> fixtures) {
    return fixtures.stream()
        .map(fixture -> {
          final String fileName = EventFixture.fileName(fixture.typeName(), fixture.version());
          return ClassUtils.isPresent(fixture.typeName(), null)
              ? new ContractViolation(
                  fileName,
                  "fixture の型 " + fixture.typeName() + " を受けるリスナーがない。",
                  "DB に古い型の出版が残る間は、その型を受けるリスナーを残す。")
              : new ContractViolation(
                  fileName,
                  "fixture の型 "
                      + fixture.typeName()
                      + " を読み込めない。型の名前かパッケージを変えると、Spring Modulith は DB に残る出版を WARN だけを出して落とす。",
                  "型の名前とパッケージを変えず、新しい型を足す。");
        })
        .toList();
  }

  /**
   * リスナーが受ける型の fixture を今の型で読み、同じ JSON に書き直すことと、必須の component が埋まることを確かめる。最も新しい版は {@code @Nullable}
   * の component も埋まることを確かめる。
   *
   * @param fixtures 版の昇順に並んだ、1 件以上の fixture
   */
  private static List<ContractViolation> readableFixtures(
      final Class<?> type,
      final List<EventFixture> fixtures,
      final String fixtureDirectory,
      final EventSerializer serializer) {
    final List<ContractViolation> violations = new ArrayList<>();
    final List<ReadFixture> reads = new ArrayList<>();
    for (final EventFixture fixture : fixtures) {
      try {
        reads.add(new ReadFixture(fixture, serializer.deserialize(fixture.json(), type)));
      } catch (final JacksonException e) {
        violations.add(
            new ContractViolation(
                EventFixture.fileName(fixture.typeName(), fixture.version()),
                "EventSerializer で " + type.getName() + " に読めない：" + e.getOriginalMessage(),
                FOLLOW_CHANGE_RULE));
      }
    }
    for (final ReadFixture read : reads) {
      roundTrip(read.fixture(), read.value(), serializer).ifPresent(violations::add);
      violations.addAll(requiredNulls(read.fixture(), read.value()));
    }
    if (!reads.isEmpty() && reads.getLast().fixture().equals(fixtures.getLast())) {
      violations.addAll(
          nullsInNewest(reads.getLast().fixture(), reads.getLast().value(), fixtureDirectory));
    }
    return violations;
  }

  /** 読んだ値を {@link EventSerializer} で書き直した JSON が、fixture と JSON の木で等しい。 */
  private static Optional<ContractViolation> roundTrip(
      final EventFixture fixture, final Object value, final EventSerializer serializer) {
    final JsonNode actual;
    try {
      actual = TREE.readTree(serializer.serialize(value).toString());
    } catch (final JacksonException e) {
      return Optional.of(
          new ContractViolation(
              EventFixture.fileName(fixture.typeName(), fixture.version()),
              "読んだ値を EventSerializer で書き直せない：" + e.getOriginalMessage(),
              FOLLOW_CHANGE_RULE));
    }
    final JsonNode expected = TREE.readTree(fixture.json());
    return expected.equals(actual)
        ? Optional.empty()
        : Optional.of(
            new ContractViolation(
                EventFixture.fileName(fixture.typeName(), fixture.version()),
                "読んで書き直した JSON が fixture と違う。component の削除、名前の変更、プリミティブの追加、spring.jackson の設定の変更で起きる。fixture="
                    + expected
                    + "、書き直した JSON="
                    + actual,
                FOLLOW_CHANGE_RULE));
  }

  /** 読んだ値の {@code @Nullable} でない component が {@code null} である（必須の component を足した）。 */
  private static List<ContractViolation> requiredNulls(
      final EventFixture fixture, final Object value) {
    return nulls(value).stream()
        .filter(component -> !component.nullable())
        .map(component ->
            new ContractViolation(
                EventFixture.fileName(fixture.typeName(), fixture.version()),
                "読んだ値の @Nullable でない component " + component.path() + " が null である。",
                FOLLOW_CHANGE_RULE))
        .toList();
  }

  /**
   * 最も新しい版の fixture を読んだ値の {@code @Nullable} の component が {@code null} である（今の形の fixture
   * を足し忘れた）。{@code @Nullable} でない component の {@code null} は {@link #requiredNulls} が挙げる。
   */
  private static List<ContractViolation> nullsInNewest(
      final EventFixture newest, final Object value, final String fixtureDirectory) {
    return nulls(value).stream()
        .filter(NullComponent::nullable)
        .map(component ->
            new ContractViolation(
                EventFixture.fileName(newest.typeName(), newest.version()),
                "最も新しい版の fixture を読んだ値の component "
                    + component.path()
                    + " が null である。今の形の fixture がない。",
                "今の型を EventSerializer で書いた JSON を、次の版の "
                    + RESOURCE_ROOT
                    + fixtureDirectory
                    + "/"
                    + EventFixture.fileName(newest.typeName(), newest.version() + 1)
                    + " に置く。"))
        .toList();
  }

  /** 読んだ値の、{@code null} の component。 */
  private static List<NullComponent> nulls(final Object value) {
    final List<NullComponent> found = new ArrayList<>();
    collectNulls(value, "", found);
    return found;
  }

  /**
   * record の component を、入れ子の record と、{@link Iterable} の要素、{@link Map} の値、配列の要素、{@link Optional}
   * の中身までたどり、値のない component を集める。
   *
   * <p>要素や値そのものの {@code null} は挙げない。fixture に書いた {@code null} であり、足した component の欠けではないためである。
   */
  // Object は sealed でないため、record、コレクション、Optional の外の値はすべて default で受ける。
  @SuppressWarnings("PMD.NoDefaultInPatternOrEnumSwitch")
  private static void collectNulls(
      final @Nullable Object value, final String path, final List<NullComponent> found) {
    switch (value) {
      case Record record -> collectComponentNulls(record, path, found);
      case Iterable<?> items -> {
        int index = 0;
        for (final Object item : items) {
          collectNulls(item, path + "[" + index + "]", found);
          index++;
        }
      }
      case Map<?, ?> map ->
          map.forEach((key, item) -> collectNulls(item, path + "[" + key + "]", found));
      case Object[] array -> {
        for (int index = 0; index < array.length; index++) {
          collectNulls(array[index], path + "[" + index + "]", found);
        }
      }
      case Optional<?> optional -> optional.ifPresent(item -> collectNulls(item, path, found));
      case null, default -> {}
    }
  }

  /** record の component の値を読み、{@code null} なら集め、そうでなければ {@link #collectNulls} でたどる。 */
  private static void collectComponentNulls(
      final Record record, final String path, final List<NullComponent> found) {
    for (final RecordComponent component : record.getClass().getRecordComponents()) {
      final String componentPath =
          path.isEmpty() ? component.getName() : path + "." + component.getName();
      final Method accessor = component.getAccessor();
      ReflectionUtils.makeAccessible(accessor);
      final Object child = ReflectionUtils.invokeMethod(accessor, record);
      if (child == null) {
        found.add(
            new NullComponent(
                componentPath, component.getAnnotatedType().isAnnotationPresent(Nullable.class)));
      } else {
        collectNulls(child, componentPath, found);
      }
    }
  }
}
