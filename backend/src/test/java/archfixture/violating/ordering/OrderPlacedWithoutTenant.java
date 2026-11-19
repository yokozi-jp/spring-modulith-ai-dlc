package archfixture.violating.ordering;

/** 違反フィクスチャが受け取る、テナントを運ばないイベント。 */
public record OrderPlacedWithoutTenant(String orderId) {}
