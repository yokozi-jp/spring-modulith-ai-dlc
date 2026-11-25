package com.example.demo.testkit.idempotency;

import com.example.demo.shared.events.ListenerIds;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * すべての {@code @ApplicationModuleListener} が、同じイベントの 2 回目（順と同時）で業務の表の行と外部システムの呼び出しを変えないことを確かめる。
 *
 * <p>違反は例外にせず一覧で返す。全件のテストは一覧が空であることを確かめ、自己検査は違反の種類を確かめる。順の 2 回の手順は {@link SequentialCheck} に、同時の 2
 * 回の手順は {@link ConcurrentCheck} にある。
 */
public final class ListenerIdempotencyChecker {

  /** すべての Bean を走査する。 */
  private final ConfigurableListableBeanFactory beanFactory;

  /** モジュールの入力例。 */
  private final List<ListenerExample> examples;

  /** 順の 2 回の検査。 */
  private final SequentialCheck sequential;

  /** 同時の 2 回の検査。 */
  private final ConcurrentCheck concurrent;

  /* package */ ListenerIdempotencyChecker(
      final ConfigurableListableBeanFactory beanFactory,
      final List<ListenerExample> examples,
      final SequentialCheck sequential,
      final ConcurrentCheck concurrent) {
    this.beanFactory = beanFactory;
    this.examples = List.copyOf(examples);
    this.sequential = sequential;
    this.concurrent = concurrent;
  }

  /** 文脈のすべての Bean から、{@code @ApplicationModuleListener} のメソッドのリスナーの ID を集める。 */
  public List<ListenerId> allListeners() {
    return ListenerIds.beanClasses(beanFactory)
        .flatMap(type -> ListenerId.listenerMethods(type).stream())
        .map(ListenerId::forMethod)
        .distinct()
        .sorted(Comparator.comparing(ListenerId::value))
        .toList();
  }

  /** 入力例のないリスナーと、リスナーのない入力例を返す。 */
  public List<Violation> checkCoverage() {
    final List<ListenerId> listeners = allListeners();
    final Set<ListenerId> covered =
        examples.stream().map(ListenerExample::listener).collect(Collectors.toSet());
    final Stream<Violation> uncovered =
        listeners.stream()
            .filter(listener -> !covered.contains(listener))
            .map(listener ->
                new Violation(
                    listener,
                    Kind.NO_EXAMPLE,
                    "the listener has no ListenerExample bean."
                        + " Add one to the module's tests and @Import it into"
                        + " ListenerIdempotencyTest"));
    final Stream<Violation> orphans =
        examples.stream()
            .filter(example -> !listeners.contains(example.listener()))
            .map(example ->
                new Violation(
                    example.listener(),
                    Kind.ORPHAN_EXAMPLE,
                    example.getClass().getName() + " names a listener that is not in the context"));
    return Stream.concat(uncovered, orphans).toList();
  }

  /** 前提を 2 つのテナントに作り、既定でないほうのテナントのイベントを順に 2 回届けて比べる。 */
  public List<Violation> checkSequential(final ListenerExample example) {
    return sequential.run(example);
  }

  /** 前提を作り、1 回目をコミットの直前で止めたまま 2 回目を届けて競合させ、1 回だけ届けたときと比べる。 */
  public List<Violation> checkConcurrent(final ListenerExample example) {
    return concurrent.run(example);
  }
}
