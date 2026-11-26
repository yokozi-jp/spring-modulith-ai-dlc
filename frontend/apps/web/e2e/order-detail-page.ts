import type { Locator, Page } from "@playwright/test";

import type { OrderStatusLabel } from "./order-status";

// 注文の詳細の画面（/orders/<注文 ID>）。画面の操作と要素を持ち、何を確かめるかは spec が持つ。
export class OrderDetailPage {
  // spec が期待値として直接使う文言。要素を探すための文言は、Locator の中に置く。
  public readonly paymentNotYetText =
    "決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。";
  // 決済の欄が自分で読み直して文言を変えるまでの上限。確定から 15 秒の読み直しと Listener の処理の時間より長くする。
  public readonly paymentTextTimeoutMs = 30_000;

  private readonly page: Page;

  public constructor(page: Page) {
    this.page = page;
  }

  public get listLink(): Locator {
    return this.page.getByRole("link", { name: "注文の一覧へ戻る" });
  }

  public get confirmButton(): Locator {
    return this.page.getByRole("button", { name: "確定する" });
  }

  public get cancelButton(): Locator {
    return this.page.getByRole("button", { name: "取り消す" });
  }

  public get discardChangesButton(): Locator {
    return this.page.getByRole("button", { name: "変更を捨てて最新を表示する" });
  }

  public get reapplyChangesButton(): Locator {
    return this.page.getByRole("button", { name: "最新の版に変更を適用し直す" });
  }

  public get rereadPaymentButton(): Locator {
    return this.page.getByRole("button", { name: "決済の状態を読み直す" });
  }

  public get quantityInput(): Locator {
    return this.linesEditor.getByLabel("数量");
  }

  public get saveLinesButton(): Locator {
    return this.linesEditor.getByRole("button", { name: "明細を保存する" });
  }

  // 明細の表の、1 行目の数量。
  public get firstLineQuantity(): Locator {
    return this.lineRows.nth(1).getByRole("cell").nth(2);
  }

  // 明細の表の行。見出しの行を含む。
  public get lineRows(): Locator {
    return this.page.getByRole("table").getByRole("row");
  }

  public get paymentRegion(): Locator {
    return this.page.getByRole("region", { name: "決済" });
  }

  // 決済の欄の状態の文言（live region）。
  public get paymentStatus(): Locator {
    return this.paymentRegion.getByRole("status");
  }

  public get confirmedNotice(): Locator {
    return this.page.getByText("注文を確定しました。");
  }

  public get cancelledNotice(): Locator {
    return this.page.getByText("注文を取り消しました。");
  }

  public get linesChangedNotice(): Locator {
    return this.page.getByText("明細を変更しました。");
  }

  public get linesConflictNotice(): Locator {
    return this.page.getByText(
      "ほかの操作で注文が更新されました。上に最新の内容を表示しています。",
    );
  }

  public get confirmConflictNotice(): Locator {
    return this.page.getByText("ほかの操作で注文が更新されました。最新の内容を表示しています。");
  }

  public get linesEditor(): Locator {
    return this.page.getByRole("form", { name: "明細の変更" });
  }

  public heading(code: string): Locator {
    return this.page.getByRole("heading", { level: 1, name: `注文 ${code}` });
  }

  public statusBadge(label: OrderStatusLabel): Locator {
    return this.page.getByText(label, { exact: true });
  }

  public async goto(orderId: string): Promise<void> {
    await this.page.goto(`/orders/${orderId}`);
  }

  public async reload(): Promise<void> {
    await this.page.reload();
  }

  public async saveQuantity(quantity: number): Promise<void> {
    await this.quantityInput.fill(String(quantity));
    await this.saveLinesButton.click();
  }

  // 同じ browser context の別のタブで、同じ注文の詳細を開く。
  public async openInNewTab(orderId: string): Promise<OrderDetailPage> {
    const other = new OrderDetailPage(await this.page.context().newPage());
    await other.goto(orderId);
    return other;
  }
}
