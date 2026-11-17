package archfixture.violating.shared.infrastructure.persistence;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

/** 違反：aspectsDeclareOrder（値のない {@code @Order} で、トランザクションの advisor と同じ順序になる）。 */
@Aspect
@Order
public class LowestOrderPgmCdAspect {}
