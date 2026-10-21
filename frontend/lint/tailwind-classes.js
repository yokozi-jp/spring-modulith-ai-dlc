// ponytail: className 属性の中のテンプレートリテラルだけを見る。className の外で組み立てた文字列や、cn() に渡す変数は見逃す。
// 見逃しが起きたら、cn() と cva() の引数も対象に加える。
// Oxlint の JS plugin API は alpha である。
const classNameAttribute = "className";

// object や配列から class を選ぶ key（`tones[`${tone}-strong`]`）は class 名ではないため、className の中でも見ない。
function isInsideClassName(node) {
  let child = node;
  let current = node.parent;
  while (current !== undefined && current !== null) {
    if (current.type === "MemberExpression" && current.property === child) {
      return false;
    }
    if (current.type === "JSXAttribute") {
      return String(current.name?.name ?? "") === classNameAttribute;
    }
    child = current;
    current = current.parent;
  }
  return false;
}

// 埋め込み式の前後が空白で区切られていなければ、式がクラス名の一部になっている。
// 式どうしが空白なしで隣り合う（`${tone}${depth}`）場合も、つながって一つのクラス名になる。
// AST は型を持たず some() の戻り値も any になるため、strict-boolean-expressions に合わせて true と比べる。
function hasPartialClassName(node) {
  return (
    node.expressions.some((_expression, index) => {
      const before = String(node.quasis[index]?.value?.raw ?? "");
      const after = String(node.quasis[index + 1]?.value?.raw ?? "");
      return /\S$/u.test(before) || /^\S/u.test(after) || (index > 0 && before === "");
    }) === true
  );
}

export const noDynamicClassName = {
  meta: {
    messages: {
      partial:
        "Do not build a Tailwind class name from a value. Tailwind only generates classes it finds as whole strings; pick a complete class from a predefined list instead.",
    },
    schema: [],
  },
  create(context) {
    return {
      TemplateLiteral(node) {
        if (isInsideClassName(node) && hasPartialClassName(node)) {
          context.report({ node, messageId: "partial" });
        }
      },
    };
  },
};

const tailwindClasses = {
  meta: { name: "tailwind-classes" },
  rules: { "no-dynamic-class-name": noDynamicClassName },
};

export default tailwindClasses;
