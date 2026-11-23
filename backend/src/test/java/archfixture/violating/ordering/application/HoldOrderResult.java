package archfixture.violating.ordering.application;

import org.jspecify.annotations.Nullable;

/** 違反：resultComponentsAreNotNullable（Result の component を @Nullable にする）。 */
public record HoldOrderResult(@Nullable String holdId) {}
