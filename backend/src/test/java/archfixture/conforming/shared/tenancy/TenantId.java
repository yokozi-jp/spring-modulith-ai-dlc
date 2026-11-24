package archfixture.conforming.shared.tenancy;

/** テナントの ID。Domain が使ってよい shared の型は、この型だけである。 */
public record TenantId(String value) {}
