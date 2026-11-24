package archfixture.violating.shared.persistence;

import org.aspectj.lang.annotation.Aspect;

/** 違反：aspectsDeclareOrder（{@code @Aspect} のクラスに {@code @Order} を付けない）。 */
@Aspect
public class UnorderedPgmCdAspect {}
