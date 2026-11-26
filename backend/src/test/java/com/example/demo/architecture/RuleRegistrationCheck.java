package com.example.demo.architecture;

import static java.util.Map.entry;
import static java.util.function.Predicate.not;

import com.example.demo.architecture.ArchTestFields.RuleField;
import com.example.demo.architecture.ViolatingFixtures.FixtureRow;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code @ArchTest} の規則が、違反の行と {@link ConformingRules} の両方に同じ規則で載るか、{@link FixtureExemptions}
 * に理由を付けて除外されていることを確かめる。
 *
 * <p>名前に対応づけた規則は、最後の because で照らす。ファクトリに別の基底パッケージを渡した規則は、説明がパッケージを含むため、インスタンスでも説明の全体でも
 * {@code @ArchTest} のフィールドと比べられない。
 *
 * @param fields {@code @ArchTest} を付けた規則のフィールド
 * @param rows 違反の行
 * @param conformingRules 規約どおりのフィクスチャで確かめる規則。キーは規則の名前
 * @param exemptions 除外する規則の名前と、除外する理由
 */
record RuleRegistrationCheck(
    List<RuleField> fields,
    List<FixtureRow> rows,
    Map<String, ArchRule> conformingRules,
    Map<String, String> exemptions) {

  /** 違反の行の場所の名前。 */
  private static final String ROWS = "eachRuleDetectsItsViolatingFixture の行";

  /** 規約どおりのフィクスチャで確かめる規則の場所の名前。 */
  private static final String CONFORMING_RULES = "ConformingRules";

  /** 除外の場所の名前。 */
  private static final String EXEMPTIONS = "FixtureExemptions";

  /** 規則を登録する場所の名前。 */
  private static final List<String> PLACES = List.of(ROWS, CONFORMING_RULES);

  /** 登録の誤りを、規則の名前で始まる文で返す。誤りがなければ空を返す。 */
  /* package */ List<String> problems() {
    return Stream.of(
            duplicateNames(),
            unregistered(),
            exemptButRegistered(),
            staleNames(),
            blankReasons(),
            wrongRules())
        .flatMap(Function.identity())
        .toList();
  }

  private Stream<String> duplicateNames() {
    return fields.stream()
        .collect(
            Collectors.groupingBy(
                RuleField::name,
                TreeMap::new,
                Collectors.mapping(RuleField::owner, Collectors.toList())))
        .entrySet()
        .stream()
        .filter(owners -> owners.getValue().size() > 1)
        .map(owners ->
            owners.getKey()
                + "："
                + owners.getValue()
                + " に同じ名前の @ArchTest がある。登録を規則の名前で照らすため。直し方：どちらかの名前を変える");
  }

  private Stream<String> unregistered() {
    return fieldsByName().keySet().stream()
        .filter(not(exemptions::containsKey))
        .flatMap(name ->
            PLACES.stream()
                .filter(place -> !namesIn(place).contains(name))
                .map(place ->
                    name
                        + "："
                        + place
                        + " にない。規則が違反を検出し、規約どおりのコードを誤検出しないことを確かめるため。"
                        + "直し方：ないほうに足す（違反の行には違反フィクスチャのクラスも足す）。"
                        + "フィクスチャで確かめないときは、FixtureExemptions に理由を付けて足す"));
  }

  private Stream<String> exemptButRegistered() {
    return exemptions.keySet().stream()
        .sorted()
        .flatMap(name ->
            PLACES.stream()
                .filter(place -> namesIn(place).contains(name))
                .map(place ->
                    name
                        + "：FixtureExemptions にあるのに "
                        + place
                        + " にある。除外した規則が片方に載ると、もう片方の漏れを除外が隠すため。"
                        + "直し方：違反の行と ConformingRules の両方に載せて FixtureExemptions から消すか、"
                        + place
                        + " から消す"));
  }

  private Stream<String> staleNames() {
    return Stream.concat(
            PLACES.stream().map(place -> entry(place, namesIn(place))),
            Stream.of(entry(EXEMPTIONS, exemptions.keySet())))
        .flatMap(place ->
            place.getValue().stream()
                .filter(not(fieldsByName()::containsKey))
                .sorted()
                .map(name ->
                    name
                        + "："
                        + place.getKey()
                        + " にあるが、この名前の @ArchTest の規則がない。誤った名前や消した規則の登録を残さないため。"
                        + "直し方：@ArchTest のフィールドの名前に直すか、登録を消す"));
  }

  private Stream<String> blankReasons() {
    return exemptions.entrySet().stream()
        .filter(exemption -> exemption.getValue().isBlank())
        .map(exemption ->
            exemption.getKey()
                + "：FixtureExemptions の理由が空。除外した理由を後から確かめられるようにするため。直し方：フィクスチャで確かめない理由を書く");
  }

  private Stream<String> wrongRules() {
    return registrations()
        .filter(registration -> fieldsByName().containsKey(registration.name()))
        .flatMap(registration -> {
          final RuleField field = fieldsByName().get(registration.name());
          final String expected = ArchTestFields.lastBecause(field.rule());
          final String actual = ArchTestFields.lastBecause(registration.rule());
          return expected.equals(actual)
              ? Stream.empty()
              : Stream.of(
                  registration.name()
                      + "："
                      + registration.place()
                      + " の規則の because が "
                      + field.owner()
                      + "."
                      + field.name()
                      + " と違う。名前と違う規則を登録すると、この規則を確かめないまま通るため。"
                      + "直し方："
                      + field.owner()
                      + "."
                      + field.name()
                      + " と同じ規則を登録する（@ArchTest の because：「"
                      + expected
                      + "」、登録した規則の because：「"
                      + actual
                      + "」）");
        });
  }

  /** 規則のフィールドを名前で引く表。同じ名前のフィールドは {@link #duplicateNames()} が報告するため、最初の一つを残す。 */
  private Map<String, RuleField> fieldsByName() {
    return fields.stream()
        .collect(
            Collectors.toMap(
                RuleField::name, Function.identity(), (first, second) -> first, TreeMap::new));
  }

  private Stream<Registration> registrations() {
    return Stream.concat(
        rows.stream().map(row -> new Registration(ROWS, row.ruleName(), row.rule())),
        conformingRules.entrySet().stream()
            .map(rule -> new Registration(CONFORMING_RULES, rule.getKey(), rule.getValue())));
  }

  private Set<String> namesIn(final String place) {
    return registrations()
        .filter(registration -> registration.place().equals(place))
        .map(Registration::name)
        .collect(Collectors.toSet());
  }

  /**
   * 場所に登録した一つの規則。
   *
   * @param place 登録した場所の名前
   * @param name 規則の名前
   * @param rule 登録した規則
   */
  private record Registration(String place, String name, ArchRule rule) {}
}
