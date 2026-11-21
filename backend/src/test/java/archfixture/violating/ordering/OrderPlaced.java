package archfixture.violating.ordering;

import archfixture.violating.shared.tenant.TenantScoped;

/** 違反フィクスチャが受け取るイベント。これ自体は規約どおり。 */
public record OrderPlaced(String orderId, String tenantId) implements TenantScoped {}
