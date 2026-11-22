package com.example.demo.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link TenantContext#call} を、入れ子、例外、例外の握りつぶしのすべての組み合わせ（式の大きさ {@value #MAX_SIZE} 以下）で動かし、MDC の
 * {@code tenant.id}、束縛、トランザクションマネージャーが読む束縛が、どの時点でも内側の束縛のテナントと一致し、最も外側を出ると何も残らないことを検証する。
 *
 * <p>手で選んだ入れ子の例と違い、組み合わせを機械的に尽くす。検証の範囲と変異は {@code
 * .agents/skills/exhaustive-verify-tenant-context/SKILL.md} にある。
 */
class TenantContextExhaustiveTest {

  /** 列挙する式の大きさの上限。ノードの数で数える。 */
  private static final int MAX_SIZE = 7;

  /** 束縛するテナント。2 つあれば、同じテナントの入れ子と違うテナントの入れ子の両方を作れる。 */
  private static final List<TenantId> TENANTS =
      List.of(
          new TenantId(UUID.fromString("5d2e8f4a-1b3c-4d6e-9f70-8a1b2c3d4e5f")),
          new TenantId(UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")));

  /** MDC の項目の名前。 */
  private static final String KEY = TenantContext.ATTRIBUTE_KEY;

  /** {@link TenantContext#call} の中で動かす式。 */
  private sealed interface Expr {

    /** 式の意味から、式が例外を外へ出すかを求める。 */
    boolean throwsOut();

    /** 今の MDC と束縛を観測する。 */
    record Probe() implements Expr {
      @Override
      public boolean throwsOut() {
        return false;
      }
    }

    /** 例外を投げる。 */
    record Throw() implements Expr {
      @Override
      public boolean throwsOut() {
        return true;
      }
    }

    /** テナントを束縛して中の式を動かす。 */
    record Call(TenantId tenant, Expr body) implements Expr {
      @Override
      public boolean throwsOut() {
        return body.throwsOut();
      }
    }

    /** 前の式の後に後の式を動かす。前が例外を投げたら後は動かさない。 */
    record Seq(Expr first, Expr second) implements Expr {
      @Override
      public boolean throwsOut() {
        return first.throwsOut() || second.throwsOut();
      }
    }

    /** 中の式が投げた例外を握りつぶす。 */
    record Catch(Expr body) implements Expr {
      @Override
      public boolean throwsOut() {
        return false;
      }
    }
  }

  /** 式の中で投げる例外。呼び出し側が同じ例外を受け取ることを確かめる。 */
  private static final class Boom extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 例外を作る。 */
    /* package */ Boom() {
      super("boom", null, false, false);
    }
  }

  @Test
  @DisplayName("どの入れ子、例外、握りつぶしの組み合わせでも、MDC と束縛は内側のテナントと一致し、最も外側を出ると何も残らない")
  void mdcAndBindingAgreeForEveryNestingUpToBound() {
    int checked = 0;
    final List<String> violations = new ArrayList<>();
    for (final Expr expr : expressionsUpTo(MAX_SIZE)) {
      violations.clear();
      final boolean thrown = runOutermost(expr, violations);
      if (thrown != expr.throwsOut()) {
        violations.add("最も外側から例外が出るかが式の意味と違う: 出た=" + thrown);
      }
      assertThat(MDC.get(KEY)).as("式 %s の後に残った MDC", expr).isNull();
      assertThat(TenantContext.findCurrent()).as("式 %s の後に残った束縛", expr).isEmpty();
      assertThat(violations).as("式 %s", expr).isEmpty();
      checked++;
    }
    assertThat(checked).as("列挙した式の数").isGreaterThan(10_000);
  }

  /** 最も外側から式を動かす。式から例外が出たかを返す。 */
  private static boolean runOutermost(final Expr expr, final List<String> violations) {
    try {
      run(expr, null, violations);
      return false;
    } catch (Boom boom) {
      return true;
    }
  }

  /** 式を動かし、{@link Expr.Probe} で MDC と束縛が、内側のテナントと一致することを確かめる。 */
  private static void run(
      final Expr expr, final @Nullable TenantId expected, final List<String> violations) {
    switch (expr) {
      case Expr.Probe() -> observe(expected, violations);
      case Expr.Throw() -> throw new Boom();
      case Expr.Call call ->
          TenantContext.<@Nullable Object, RuntimeException>call(
              call.tenant(),
              () -> {
                run(call.body(), call.tenant(), violations);
                return null;
              });
      case Expr.Seq seq -> {
        run(seq.first(), expected, violations);
        run(seq.second(), expected, violations);
      }
      case Expr.Catch caught -> {
        try {
          run(caught.body(), expected, violations);
        } catch (Boom swallowed) {
          // 握りつぶした後も、束縛と MDC は外側のテナントに戻っている必要がある。
          observe(expected, violations);
        }
      }
    }
  }

  /** 今の MDC、束縛が、期待するテナントと一致するかを確かめる。 */
  private static void observe(final @Nullable TenantId expected, final List<String> violations) {
    final @Nullable String expectedText = expected == null ? null : expected.toString();
    final @Nullable String mdc = MDC.get(KEY);
    if (!Objects.equals(mdc, expectedText)) {
      violations.add("MDC tenant.id=" + mdc + " だが内側の束縛は " + expectedText);
    }
    final @Nullable String bound = TenantContext.findCurrent().map(TenantId::toString).orElse(null);
    if (!Objects.equals(bound, expectedText)) {
      violations.add("束縛=" + bound + " だが内側の束縛は " + expectedText);
    }
  }

  /** 大きさ（ノードの数）が {@code maxSize} 以下のすべての式を作る。 */
  private static List<Expr> expressionsUpTo(final int maxSize) {
    final List<List<Expr>> bySize = new ArrayList<>();
    bySize.add(List.of());
    bySize.add(List.of(new Expr.Probe(), new Expr.Throw()));
    for (int size = 2; size <= maxSize; size++) {
      bySize.add(expressionsOfSize(bySize, size));
    }
    return bySize.stream().flatMap(List::stream).toList();
  }

  /** 大きさがちょうど {@code size} の式を、より小さい式（{@code bySize}）から作る。 */
  private static List<Expr> expressionsOfSize(final List<List<Expr>> bySize, final int size) {
    final List<Expr> level = new ArrayList<>();
    for (final Expr body : bySize.get(size - 1)) {
      TENANTS.forEach(tenant -> level.add(new Expr.Call(tenant, body)));
      level.add(new Expr.Catch(body));
    }
    for (int left = 1; left < size - 1; left++) {
      for (final Expr first : bySize.get(left)) {
        bySize.get(size - 1 - left).forEach(second -> level.add(new Expr.Seq(first, second)));
      }
    }
    return level;
  }
}
