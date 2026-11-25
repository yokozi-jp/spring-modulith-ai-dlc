export const seededOrderCodesByStatusLabel = {
  下書き: ["SEED-C01", "SEED-C02"],
  確定済み: ["SEED-C03", "SEED-C05", "SEED-C06"],
  取消済み: ["SEED-C04"],
};

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
