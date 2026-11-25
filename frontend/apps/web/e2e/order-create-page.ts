import type { Locator, Page } from "@playwright/test";

// 注文の作成の画面（/orders/new）。画面の操作と要素を持ち、何を確かめるかは spec が持つ。
export class OrderCreatePage {
  public readonly productSelect: Locator;
  private readonly page: Page;

  public constructor(page: Page) {
    this.page = page;
    this.productSelect = page.getByLabel("商品");
  }

  public async goto(): Promise<void> {
    await this.page.goto("/orders/new");
  }

  // 商品 1 つの明細の下書きを作り、詳細の画面の注文 ID を返す。
  // beforeSubmit は、フォームを埋めてから「作成する」を押す直前に呼ぶ。
  public async create(
    order: { code: string; productName: string; quantity: number },
    beforeSubmit?: () => Promise<void>,
  ): Promise<string> {
    await this.goto();
    await this.page.getByLabel("客先注文番号").fill(order.code);
    // 作成の画面は空の明細を 1 行持って開くので、明細を追加せずにその行を埋める。
    await this.productSelect.selectOption({ label: order.productName });
    await this.page.getByLabel("数量").fill(String(order.quantity));
    await beforeSubmit?.();
    await this.page.getByRole("button", { name: "作成する" }).click();
    await this.page.waitForURL(/\/orders\/[0-9a-f-]{36}$/u);
    return new URL(this.page.url()).pathname.slice("/orders/".length);
  }
}
