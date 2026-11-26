package com.example.demo.eventcontract;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
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

  /** fixture の置き場所。直し方のメッセージに使う。 */
  private static final String FIXTURE_DIR = "backend/src/test/resources/events/";

  /** 型の名前とパッケージを変えたときの直し方。 */
  private static final String KEEP_TYPE_NAME =
      "型の名前とパッケージを変えず、新しい型を足す。DB に古い型の出版が残る間は、古い型とそれを受けるリスナーを残す。";

  /** JSON の木の比較だけに使う、設定を持たない mapper。 */
  private static final JsonMapper TREE = JsonMapper.shared();

  private EventContract() {}

  /**
   * 1 件の fixture。
   *
   * @param typeName ファイル名が示すイベントの型の完全な名前
   * @param version ファイル名が示す版（1 から）
   * @param json ファイルの中身
   * @param fileName ファイル名
   */
  /* package */ record EventFixture(String typeName, int version, String json, String fileName) {

    /** 型と版から fixture のファイル名を作る。 */
    /* package */ static String fileName(final String typeName, final int version) {
      return typeName + ".v" + version + ".json";
    }
  }

  /**
   * 規則の違反。
   *
   * @param fixture 違反した fixture のファイル名（ない fixture は、置くべきファイル名）
   * @param problem 何が悪いか
   * @param fix 直し方
   */
  /* package */ record Violation(String fixture, String problem, String fix) {

    @Override
    public String toString() {
      return fixture + "：" + problem + " 直し方：" + fix;
    }
  }

  /** 読んだ値で {@code null} だった component。 */
  private record NullComponent(String path, boolean nullable) {}

  /**
   * クラスパスのディレクトリの fixture を読み、ファイル名の違反と {@link #check} の違反を返す。
   *
   * @param listenedEventTypes {@code @ApplicationModuleListener} が受けるイベントの型
   * @param directory fixture を置くクラスパスのディレクトリ
   * @param serializer 本番と同じ {@link EventSerializer}
   * @return 違反。ない場合は空
   */
  /* package */ static List<Violation> checkDirectory(
      final Set<Class<?>> listenedEventTypes,
      final String directory,
      final EventSerializer serializer) {
    final EventFixtures.Loaded loaded = EventFixtures.load(directory);
    final List<Violation> violations = new ArrayList<>(loaded.violations());
    violations.addAll(check(listenedEventTypes, loaded.fixtures(), serializer));
    return violations;
  }

  /**
   * リスナーが受ける型と fixture を照らし、違反を返す。
   *
   * @param listenedEventTypes {@code @ApplicationModuleListener} が受けるイベントの型
   * @param fixtures 名前を解釈した fixture
   * @param serializer 本番と同じ {@link EventSerializer}
   * @return 違反。ない場合は空
   */
  /* package */ static List<Violation> check(
      final Set<Class<?>> listenedEventTypes,
      final List<EventFixture> fixtures,
      final EventSerializer serializer) {
    final Map<String, Class<?>> listened =
        listenedEventTypes.stream()
            .collect(toMap(Class::getName, Function.identity(), (a, b) -> a, TreeMap::new));
    final Map<String, List<EventFixture>> byType =
        fixtures.stream()
            .sorted(Comparator.comparingInt(EventFixture::version))
            .collect(groupingBy(EventFixture::typeName, TreeMap::new, toList()));
    final List<Violation> violations = new ArrayList<>();
    listened.keySet().forEach(typeName -> checkVersions(typeName, byType, violations));
    byType.forEach((typeName, versions) -> {
      final int newest = versions.getLast().version();
      versions.forEach(fixture ->
          checkFixture(
              fixture,
              listened.get(typeName),
              fixture.version() == newest,
              serializer,
              violations));
    });
    return violations;
  }

  /** リスナーが受ける型に、v1 から欠けずに続く fixture がある。 */
  private static void checkVersions(
      final String typeName,
      final Map<String, List<EventFixture>> byType,
      final List<Violation> violations) {
    final Set<Integer> versions = new TreeSet<>();
    byType.getOrDefault(typeName, List.of()).forEach(fixture -> versions.add(fixture.version()));
    final int newest = versions.stream().max(Integer::compare).orElse(1);
    for (int version = 1; version <= newest; version++) {
      if (!versions.contains(version)) {
        violations.add(
            new Violation(
                EventFixture.fileName(typeName, version),
                "リスナーが受ける型 " + typeName + " の v" + version + " の fixture がない。",
                "版は v1 から欠けずに続ける。v1 がないなら、今の型を EventSerializer で書いた JSON を "
                    + FIXTURE_DIR
                    + EventFixture.fileName(typeName, 1)
                    + " に置く。途中の版がないなら、消した fixture を戻す。"));
      }
    }
  }

  /** fixture の型にリスナーがあり、今の型で読め、同じ JSON に書き直し、必須の component が埋まる。最も新しい版はすべての component が埋まる。 */
  private static void checkFixture(
      final EventFixture fixture,
      final @Nullable Class<?> type,
      final boolean newest,
      final EventSerializer serializer,
      final List<Violation> violations) {
    if (type == null) {
      violations.add(
          new Violation(
              fixture.fileName(),
              ClassUtils.isPresent(fixture.typeName(), null)
                  ? "fixture の型 " + fixture.typeName() + " を受けるリスナーがない。"
                  : "fixture の型 "
                      + fixture.typeName()
                      + " を読み込めない。型の名前かパッケージを変えると、Spring Modulith は DB に残る出版を WARN だけを出して落とす。",
              KEEP_TYPE_NAME));
      return;
    }
    final Object value;
    try {
      value = serializer.deserialize(fixture.json(), type);
    } catch (final JacksonException e) {
      violations.add(
          new Violation(
              fixture.fileName(),
              "EventSerializer で " + type.getName() + " に読めない：" + e.getOriginalMessage(),
              "component の型を戻す。型を変えたいなら、新しい型のイベントを足す。"));
      return;
    }
    final JsonNode expected = TREE.readTree(fixture.json());
    final JsonNode actual = TREE.readTree(serializer.serialize(value).toString());
    if (!expected.equals(actual)) {
      violations.add(
          new Violation(
              fixture.fileName(),
              "読んで書き直した JSON が fixture と違う。fixture=" + expected + "、書き直した JSON=" + actual,
              "component の削除と名前の変更、spring.jackson の設定の変更を戻す。足すのは @Nullable の参照型の component"
                  + " だけにし、プリミティブを足さない。形を変えたいなら新しい型のイベントを足す。過去の fixture は書き換えない。"));
    }
    final List<NullComponent> nulls = new ArrayList<>();
    collectNulls(value, "", nulls);
    for (final NullComponent component : nulls) {
      if (component.nullable()) {
        if (newest) {
          violations.add(
              new Violation(
                  fixture.fileName(),
                  "最も新しい版の fixture を読んだ値の component "
                      + component.path()
                      + " が null である。今の形の fixture がない。",
                  "今の型を EventSerializer で書いた JSON を、次の版の "
                      + FIXTURE_DIR
                      + EventFixture.fileName(fixture.typeName(), fixture.version() + 1)
                      + " に足す。過去の fixture は書き換えない。"));
        }
      } else {
        violations.add(
            new Violation(
                fixture.fileName(),
                "読んだ値の @Nullable でない component " + component.path() + " が null である。",
                "component の名前を変えたなら戻す。足した component なら @Nullable を付けるか、必須の項目なら新しい型のイベントを足す。"));
      }
    }
  }

  /**
   * record の component を入れ子の record までたどり、値のない component を集める。
   *
   * <p>ponytail: コレクション、{@link Map}、配列の中はたどらない。イベントにそれらを持たせるときに足す。
   */
  private static void collectNulls(
      final Object value, final String path, final List<NullComponent> found) {
    if (!(value instanceof Record)) {
      return;
    }
    for (final RecordComponent component : value.getClass().getRecordComponents()) {
      final String componentPath =
          path.isEmpty() ? component.getName() : path + "." + component.getName();
      final Object child = ReflectionUtils.invokeMethod(component.getAccessor(), value);
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
