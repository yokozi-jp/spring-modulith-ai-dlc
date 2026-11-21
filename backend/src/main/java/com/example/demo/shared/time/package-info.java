/**
 * ユースケースの現在時刻を置く。1 つのユースケースの中で時計を 1 回だけ読み、その値を呼び出しの間だけ束縛する {@link
 * com.example.demo.shared.time.UseCaseTime} である（ADR-109）。束縛は {@code infrastructure.persistence} の
 * {@code PgmCdAspect} が行う。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。業務の概念を置かず、DB に依存しない。
 */
@NamedInterface("time")
@NullMarked
package com.example.demo.shared.time;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
