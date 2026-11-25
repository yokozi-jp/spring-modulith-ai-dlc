package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * GET と HEAD 以外の業務の API が {@code Idempotency-Key} を受け取り、その Command の CommandHandler が保存した Result
 * を返す入口を通ることを検査する（ADR-126）。
 *
 * <p>規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 */
// ArchUnit の JUnit 5 エンジンが @ArchTest のフィールドを読むため、規則ファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.UseUtilityClass"})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class IdempotencyArchTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /**
   * キーをまだ受け取らない、一時的に除く操作（基底パッケージより下の {@code <クラス>.<メソッド>}）。
   *
   * <p>{@code tenancy} の所属の操作は、RLS のない表にキーを置く #540 のスライス 3b で対応し、そのときにこの一覧から消す。消し忘れは {@link
   * #temporaryExclusionsStillLackTheKey} が止める。商品の登録は、{@code product} のスキーマにキーの表を足す #759
   * まで除く（ADR-129）。
   */
  private static final Set<String> NOT_YET_IDEMPOTENT =
      Set.of(
          "tenancy.presentation.web.CurrentTenantMemberController.changeRole",
          "tenancy.presentation.web.CurrentTenantMemberController.remove",
          "product.presentation.web.ProductController.register");

  /**
   * {@code Idempotency-Key} を求める HTTP メソッドの注釈。
   *
   * <p>ponytail: {@code @RequestMapping(method = ...)} で書いたマッピングは見ない。使い始めたら、その {@code method}
   * も判定に足す。
   */
  private static final List<Class<? extends Annotation>> UNSAFE_MAPPINGS =
      List.of(PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

  /** キーを求めない operation の印の拡張の名前と値。 */
  private static final String NO_KEY_EXTENSION = "x-idempotency-key";

  /** 規則と規約の文書。 */
  private static final String DOCS =
      "docs/backend/class-roles/controller.md、docs/backend/class-roles/command-handler.md、"
          + "docs/adr/ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md";

  /** GET と HEAD 以外の Controller のメソッドは、パス変数の次の引数で {@code IdempotencyKey} を受け取る。 */
  @ArchTest
  /* package */ static final ArchRule unsafeHandlersTakeIdempotencyKey =
      unsafeHandlersTakeIdempotencyKeyRule(BASE_PACKAGE, NOT_YET_IDEMPOTENT);

  /**
   * {@code IdempotentCommand} を受け取る CommandHandler の {@code handle} は、{@code Idempotency.execute}
   * を呼ぶ。
   */
  @ArchTest
  /* package */ static final ArchRule idempotentCommandHandlersExecuteThroughIdempotency =
      idempotentCommandHandlersExecuteThroughIdempotencyRule(BASE_PACKAGE);

  /** 一時的に除く操作は、まだキーを受け取っていない。受け取ったら一覧から消させる。 */
  @ArchTest
  /* package */ static final ArchRule temporaryExclusionsStillLackTheKey =
      methods()
          .that(
              DescribedPredicate.describe(
                  "NOT_YET_IDEMPOTENT に挙げた操作",
                  method -> NOT_YET_IDEMPOTENT.contains(operationName(BASE_PACKAGE, method))))
          .should(
              ArchConditions.not(
                  takeIdempotencyKeyAfterPathVariables(
                      BASE_PACKAGE + ".shared.idempotency.IdempotencyKey")))
          .because(
              "キーを受け取った操作を一覧に残すと、後で外したキーを unsafeHandlersTakeIdempotencyKey が見落とすため。"
                  + "直し方：キーを受け取った操作を IdempotencyArchTest の NOT_YET_IDEMPOTENT から消す。"
                  + "規約："
                  + DOCS);

  /**
   * キーを受け取る Controller の規則を組み立てる。
   *
   * @param basePackage 基底パッケージ
   * @param notYetIdempotent 一時的に除く操作（基底パッケージより下の {@code <クラス>.<メソッド>}）
   */
  /* package */ static ArchRule unsafeHandlersTakeIdempotencyKeyRule(
      final String basePackage, final Set<String> notYetIdempotent) {
    final String idempotencyKey = basePackage + ".shared.idempotency.IdempotencyKey";
    return methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage(basePackage + ".*.presentation.web..")
        .and(
            DescribedPredicate.describe(
                "@PostMapping、@PutMapping、@PatchMapping、@DeleteMapping を付け、"
                    + "x-idempotency-key: none の印がなく、一時的に除く操作でない",
                method ->
                    UNSAFE_MAPPINGS.stream().anyMatch(method::isAnnotatedWith)
                        && !marksNoKey(method)
                        && !notYetIdempotent.contains(operationName(basePackage, method))))
        .should(takeIdempotencyKeyAfterPathVariables(idempotencyKey))
        .allowEmptyShould(true)
        .because(
            "キーを受け取らない更新の API は、成功して応答を失った再送が 409 になり、契約が求めるヘッダーも読まれないため。"
                + "キーを本文より前に置かないと、キーの誤りより本文の検証の 400 が先に返るため。"
                + "直し方：パス変数の次の引数を注釈のない final IdempotencyKey idempotencyKey にし、Request の toCommand に渡す。"
                + "テナントを選ぶ前の操作だけは @Operation(extensions = ... x-idempotency-key: none) を付ける。"
                + "規約："
                + DOCS);
  }

  /** {@code Idempotency.execute} を呼ぶ CommandHandler の規則を組み立てる。 */
  /* package */ static ArchRule idempotentCommandHandlersExecuteThroughIdempotencyRule(
      final String basePackage) {
    final String idempotentCommand = basePackage + ".shared.idempotency.IdempotentCommand";
    final String idempotency = basePackage + ".shared.idempotency.Idempotency";
    return classes()
        .that()
        .haveSimpleNameEndingWith("CommandHandler")
        .should(callIdempotencyExecuteInHandle(idempotentCommand, idempotency))
        .allowEmptyShould(true)
        .because(
            "IdempotentCommand を受け取っても Idempotency.execute を通らないと、同じキーの再送で業務をもう一度実行するため。"
                + "直し方：handle を return idempotency.execute(command, <UseCase>Result.class, () -> <本体>(command));"
                + " にし、本体を private メソッドに置く。"
                + "規約："
                + DOCS);
  }

  /** 基底パッケージより下の {@code <クラス>.<メソッド>} を返す。 */
  private static String operationName(final String basePackage, final JavaMethod method) {
    return method.getOwner().getName().substring(basePackage.length() + 1) + "." + method.getName();
  }

  /** {@code @Operation} の拡張に {@code x-idempotency-key: none} があるかを返す。 */
  private static boolean marksNoKey(final JavaMethod method) {
    return method
        .tryGetAnnotationOfType(Operation.class)
        .map(operation ->
            Arrays.stream(operation.extensions())
                .flatMap(extension -> Arrays.stream(extension.properties()))
                .anyMatch(property ->
                    NO_KEY_EXTENSION.equals(property.name()) && "none".equals(property.value())))
        .orElse(false);
  }

  private static ArchCondition<JavaMethod> takeIdempotencyKeyAfterPathVariables(
      final String idempotencyKey) {
    return new ArchCondition<>("take IdempotencyKey as the first parameter after path variables") {
      @Override
      public void check(final JavaMethod method, final ConditionEvents events) {
        final List<JavaParameter> afterPath =
            method.getParameters().stream()
                .dropWhile(parameter -> parameter.isAnnotatedWith(PathVariable.class))
                .toList();
        if (afterPath.isEmpty()
            || !afterPath.getFirst().getRawType().getName().equals(idempotencyKey)) {
          events.add(
              SimpleConditionEvent.violated(
                  method, method.getFullName() + " がパス変数の次の引数で IdempotencyKey を受け取っていない。"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> callIdempotencyExecuteInHandle(
      final String idempotentCommand, final String idempotency) {
    return new ArchCondition<>("call Idempotency.execute in handle(IdempotentCommand)") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        item.getMethods().stream()
            .filter(method -> "handle".equals(method.getName()))
            .filter(method -> method.getRawParameterTypes().size() == 1)
            .filter(method ->
                method.getRawParameterTypes().getFirst().isAssignableTo(idempotentCommand))
            .filter(method ->
                method.getMethodCallsFromSelf().stream()
                    .noneMatch(call ->
                        "execute".equals(call.getName())
                            && call.getTargetOwner().isAssignableTo(idempotency)))
            .forEach(method ->
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        method.getFullName() + " が Idempotency.execute を handle の中で呼んでいない。")));
      }
    };
  }
}
