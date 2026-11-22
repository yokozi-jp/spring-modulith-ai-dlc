package com.example.demo.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link TenantContext#call} を、入れ子、例外（検査されない例外、検査される例外、{@link Error}）、例外の握りつぶしのすべての組み合わせ（式の大きさ
 * {@value #MAX_SIZE} 以下）で動かし、MDC の {@code
 * tenant.id}、束縛、トランザクションマネージャーが読む束縛が、どの時点でも内側の束縛のテナントと一致し、最も外側を出ると何も残らず、例外がそのまま出ることを検証する。
 *
 * <p>{@link TenantContextTest} は読みやすい代表例で、この検査はその例を含めて組み合わせを機械的に尽くす。検証の範囲と変異は {@code
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

  /** MDC の項目の名前。{@code TenantContext.ATTRIBUTE_KEY} の定数名が変わっても、ログの項目名が変わらないよう、値をここに固定する。 */
  private static final String KEY = "tenant.id";

  /** 例外をまだ投げていないことを表す。 */
  private static final Throwable NOTHING_THROWN = new IllegalStateException("nothing thrown");

  /** 投げる例外の種類。 */
  private enum Kind {
    /** 検査されない例外。 */
    UNCHECKED,
    /** 検査される例外。リクエストの filter が通す {@code ServletException} に当たる。 */
    CHECKED,
    /** {@link Error}（{@link AssertionError} で代表する）。 */
    ERROR
  }

  /** 投げる検査される例外。 */
  private static final class CheckedBoom extends Exception {

    private static final long serialVersionUID = 1L;

    /* package */ CheckedBoom() {
      super("checked", null, false, false);
    }
  }

  /** 投げる検査されない例外。 */
  private static final class UncheckedBoom extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /* package */ UncheckedBoom() {
      super("unchecked", null, false, false);
    }
  }

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
    record Throw(Kind kind) implements Expr {
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

    /**
     * 前の式の後に後の式を動かす。前が例外を投げたら後は動かさない。結合則が成り立つため、{@code Seq(Seq(a,b),c)} と {@code Seq(a,Seq(b,c))}
     * は重複する。
     */
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

  @AfterEach
  void clearMdc() {
    // 退行でテナントが残っても、同じ JVM の別のテストの失敗にしない。
    MDC.remove(KEY);
  }

  @Test
  @DisplayName("どの入れ子、例外、握りつぶしの組み合わせでも、MDC と束縛は内側のテナントと一致し、最も外側を出ると何も残らず、例外はそのまま出る")
  void mdcAndBindingAgreeForEveryNestingUpToBound() {
    final List<Expr> expressions = expressionsUpTo(MAX_SIZE);
    final Run run = new Run();
    for (final Expr expr : expressions) {
      run.reset();
      final @Nullable Throwable escaped = run.outermost(expr);
      final boolean thrown = escaped != null;
      if (thrown == expr.throwsOut()) {
        if (thrown && !escaped.equals(run.lastThrown)) {
          run.violations.add("ExceptionUnchanged: 最後に投げた例外と別の例外が出た。出た例外=" + escaped);
        }
      } else {
        run.violations.add("ExceptionUnchanged: 例外が出るかが式の意味と違う。出た例外=" + escaped);
      }
      if (MDC.get(KEY) != null) {
        run.violations.add("NothingRemains: 最も外側を出た後に MDC が残る。MDC=" + MDC.get(KEY));
      }
      if (TenantContext.findCurrent().isPresent()) {
        run.violations.add("NothingRemains: 最も外側を出た後に束縛が残る");
      }
      assertThat(run.violations).as("式 %s", expr).isEmpty();
    }
    assertThat(expressions).as("列挙した式").hasSize(49_764);
  }

  /** 式を一つ動かし、観測の食い違いを集める。 */
  private static final class Run {

    /** 食い違いの説明。約束の名前で始める。 */
    private final List<String> violations = new ArrayList<>();

    /** 最後に投げた例外。外へ出る例外は、いつもこれである。 */
    private Throwable lastThrown = NOTHING_THROWN;

    /** 次の式のために、食い違いと最後に投げた例外を消す。 */
    private void reset() {
      violations.clear();
      lastThrown = NOTHING_THROWN;
    }

    /** 最も外側から式を動かす。式から出た例外を返す。 */
    private @Nullable Throwable outermost(final Expr expr) {
      try {
        run(expr, null);
        return null;
      } catch (CheckedBoom | UncheckedBoom | AssertionError thrown) {
        return thrown;
      }
    }

    /** 式を動かし、{@link Expr.Probe} で MDC と束縛が、内側のテナントと一致することを確かめる。 */
    private void run(final Expr expr, final @Nullable TenantId expected) throws CheckedBoom {
      switch (expr) {
        case Expr.Probe() -> observe(expected);
        case Expr.Throw(var kind) -> throwNew(kind);
        case Expr.Call call ->
            TenantContext.<@Nullable Object, CheckedBoom>call(
                call.tenant(),
                () -> {
                  run(call.body(), call.tenant());
                  return null;
                });
        case Expr.Seq seq -> {
          run(seq.first(), expected);
          run(seq.second(), expected);
        }
        case Expr.Catch caught -> {
          try {
            run(caught.body(), expected);
          } catch (CheckedBoom | UncheckedBoom | AssertionError swallowed) {
            observe(expected);
          }
        }
      }
    }

    private void throwNew(final Kind kind) throws CheckedBoom {
      switch (kind) {
        case UNCHECKED -> {
          final UncheckedBoom thrown = new UncheckedBoom();
          lastThrown = thrown;
          throw thrown;
        }
        case CHECKED -> {
          final CheckedBoom thrown = new CheckedBoom();
          lastThrown = thrown;
          throw thrown;
        }
        case ERROR -> {
          final AssertionError thrown = new AssertionError("error");
          lastThrown = thrown;
          throw thrown;
        }
      }
    }

    /** 今の MDC、束縛が、期待するテナントと一致するかを確かめる。 */
    private void observe(final @Nullable TenantId expected) {
      final @Nullable String expectedText = expected == null ? null : expected.toString();
      final @Nullable String mdc = MDC.get(KEY);
      if (!Objects.equals(mdc, expectedText)) {
        violations.add("MdcFollowsBinding: MDC tenant.id=" + mdc + " だが内側の束縛は " + expectedText);
      }
      final @Nullable String bound =
          TenantContext.findCurrent().map(TenantId::toString).orElse(null);
      if (!Objects.equals(bound, expectedText)) {
        violations.add("MdcFollowsBinding: 束縛=" + bound + " だが内側の束縛は " + expectedText);
      }
    }
  }

  /** 大きさ（ノードの数）が {@code maxSize} 以下のすべての式を作る。 */
  private static List<Expr> expressionsUpTo(final int maxSize) {
    final List<List<Expr>> bySize = new ArrayList<>();
    bySize.add(List.of());
    bySize.add(
        List.of(
            new Expr.Probe(),
            new Expr.Throw(Kind.UNCHECKED),
            new Expr.Throw(Kind.CHECKED),
            new Expr.Throw(Kind.ERROR)));
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
