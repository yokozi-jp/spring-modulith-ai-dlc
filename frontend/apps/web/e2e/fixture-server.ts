import type { APIRequestContext } from "@playwright/test";

import { fixtureOrigin, tenantName } from "./environment.ts";

// fixture サーバー（backend の E2eFixtureServer、ADR-129）が作った商品。
export interface Product {
  productId: string;
  productCode: string;
  productName: string;
  unitPrice: number;
  onSale: boolean;
}

// ログインの後に選ぶテナントに、このテストだけが使う商品を作る。商品コードと商品名は呼ぶたびに一意になる。
export async function createProduct(
  request: APIRequestContext,
  { onSale = true, unitPrice = 1200 }: { onSale?: boolean; unitPrice?: number } = {},
): Promise<Product> {
  const response = await request.post(`${fixtureOrigin}/products`, {
    data: { tenant: tenantName, onSale, unitPrice },
  });
  if (response.status() !== 201) {
    throw new Error(
      `POST ${fixtureOrigin}/products が ${response.status()} を返しました：${await response.text()}`,
    );
  }
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- Playwright の response.json() は any を返す。形は E2eFixtureServer の CreatedProduct が決める。
  return (await response.json()) as Product;
}
