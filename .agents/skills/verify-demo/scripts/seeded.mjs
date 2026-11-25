// 開発用のシーダー（task be-seed）が入れた代表データを読む補助。stack.sh up がシーダーを実行する。
// E2E はシーダーを使わず各テストがデータを作る（ADR-129）ので、代表データを読む補助は検証スキルだけが持つ。
// drive.mjs が e2e/orders.ts の export と合わせて journey の orders に渡す。

// 一覧の状態の絞り込みのリンクの名前ごとの、シーダーの代表の注文（SEED-C01 から SEED-C06）。
export const seededOrderCodesByStatusLabel = {
  下書き: ["SEED-C01", "SEED-C02"],
  確定済み: ["SEED-C03", "SEED-C05", "SEED-C06"],
  取消済み: ["SEED-C04"],
};

// シーダーの商品名は乱数の値なので、名前を固定で書かず GET /api/products（CSRF の token は要らない）から選ぶ。
export async function listProducts(request) {
  const response = await request.get("/api/products");
  if (response.status() !== 200) throw new Error(`GET /api/products が ${response.status()} を返しました`);
  return (await response.json()).items;
}

export async function onSaleProduct(request) {
  const product = (await listProducts(request)).find((item) => item.salesStatus === "ON_SALE");
  if (product === undefined) {
    throw new Error("販売中の商品がありません。stack.sh doctor で代表データを確かめてください。");
  }
  return product;
}
