package com.example.demo.shared.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.modulith.events.AbandonPolicy;
import org.springframework.modulith.events.EventPublication;

/** Spring Modulith が諦めを判断するときに渡す出版の値を記録し、判断は Spring Modulith の既定に任せる。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
final class AbandonPolicyRecorder implements AbandonPolicy {

  /** 出版の ID ごとの、渡された値の並び。 */
  private final Map<UUID, List<Seen>> seenById = new ConcurrentHashMap<>();

  @Override
  public Decision shouldAbandon(final EventPublication publication) {
    seenById
        .computeIfAbsent(publication.getIdentifier(), id -> new CopyOnWriteArrayList<>())
        .add(Seen.of(publication));
    return Decision.DEFAULT;
  }

  /* package */ List<Seen> seen(final UUID publication) {
    return List.copyOf(seenById.getOrDefault(publication, List.of()));
  }

  /** {@link AbandonPolicy} から見える出版の値。 */
  /* package */ record Seen(
      EventPublication.Status status,
      @Nullable Instant lastResubmissionDate,
      Instant publicationDate,
      int completionAttempts,
      Instant lastPublicationDate) {

    /* package */ static Seen of(final EventPublication publication) {
      return new Seen(
          publication.getStatus(),
          publication.getLastResubmissionDate(),
          publication.getPublicationDate(),
          publication.getCompletionAttempts(),
          publication.getLastPublicationDate());
    }
  }
}
