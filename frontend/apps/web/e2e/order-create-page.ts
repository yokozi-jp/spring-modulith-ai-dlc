import type { Locator, Page } from "@playwright/test";

interface OrderInput {
  code: string;
  productName: string;
  quantity: number;
}

// 注文の作成の画面（/orders/new）。画面の操作と要素を持ち、何を確かめるかは spec が持つ。
export class OrderCreatePage {
  private readonly page: Page;

  public constructor(page: Page) {
    this.page = page;
  }

  public get heading(): Locator {
    return this.page.getByRole("heading", { level: 1, name: "注文の作成" });
  }

  public get customerOrderCodeInput(): Locator {
    return this.page.getByLabel("客先注文番号");
  }

  // 空の明細 1 行の商品の選択肢。
  public get productSelect(): Locator {
    return this.page.getByLabel("商品");
  }

  public get quantityInput(): Locator {
    return this.page.getByLabel("数量");
  }

  public get addLineButton(): Locator {
    return this.page.getByRole("button", { name: "明細を追加する" });
  }

  public get submitButton(): Locator {
    return this.page.getByRole("button", { name: "作成する" });
  }

  // 明細の行。number は 1 から数える。
  public line(number: number): Locator {
    return this.page.getByRole("group", { name: `明細 ${number}` });
  }

  public removeLineButton(number: number): Locator {
    return this.page.getByRole("button", { name: `明細 ${number} を削除する` });
  }

  public async goto(): Promise<void> {
    await this.page.goto("/orders/new");
  }

  // 作成の画面は空の明細を 1 行持って開くので、明細を追加せずにその行を埋める。
  public async fill(order: OrderInput): Promise<void> {
    await this.customerOrderCodeInput.fill(order.code);
    await this.productSelect.selectOption({ label: order.productName });
    await this.quantityInput.fill(String(order.quantity));
  }

  // 商品 1 つの明細の下書きを作り、詳細の画面の注文 ID を返す。
  // beforeSubmit は、フォームを埋めてから「作成する」を押す直前に呼ぶ。
  public async create(order: OrderInput, beforeSubmit?: () => Promise<void>): Promise<string> {
    await this.goto();
    await this.fill(order);
    await beforeSubmit?.();
    await this.submitButton.click();
    await this.page.waitForURL(/\/orders\/[0-9a-f-]{36}$/u);
    return new URL(this.page.url()).pathname.slice("/orders/".length);
  }
}
