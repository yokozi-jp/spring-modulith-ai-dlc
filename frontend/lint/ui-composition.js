// ponytail: 要素を名前（Link、a、Button、button）だけで判定する。別名で import した component は見逃す。
// render に渡す関数は、直接返す JSX だけを見る。変数に入れてから返す要素や、条件演算子で返す要素は見逃す。
// 見逃しが起きたら、import の解決元（@tanstack/react-router、@/components/ui/button）で判定する規則に移す。
// Oxlint の JS plugin API は alpha である。
const linkNames = new Set(["Link", "a"]);
const buttonNames = new Set(["Button", "button"]);
const functionTypes = new Set(["ArrowFunctionExpression", "FunctionExpression"]);

function elementName(openingElement) {
  return openingElement?.name?.type === "JSXIdentifier" ? String(openingElement.name.name) : "";
}

// render は要素と、要素を返す関数（`(props) => <Link {...props} />`）のどちらも受け取る。
// AST は型を持たず some() の戻り値も any になるため、strict-boolean-expressions に合わせて true と比べる。
/** @returns {boolean} */
function rendersLink(expression) {
  if (expression?.type === "JSXElement") {
    return linkNames.has(elementName(expression.openingElement));
  }
  if (!functionTypes.has(String(expression?.type))) {
    return false;
  }
  if (expression.body?.type !== "BlockStatement") {
    return rendersLink(expression.body);
  }
  return (
    expression.body.body.some(
      (statement) => statement.type === "ReturnStatement" && rendersLink(statement.argument),
    ) === true
  );
}

function isInsideLink(node) {
  let current = node.parent;
  while (current !== undefined && current !== null) {
    if (current.type === "JSXElement" && linkNames.has(elementName(current.openingElement))) {
      return true;
    }
    current = current.parent;
  }
  return false;
}

export const noButtonInLink = {
  meta: {
    messages: {
      nested: "Do not put a button inside a link. Style the link with buttonVariants instead.",
      render:
        "Do not pass a link to render of Button; Base UI overrides its role to button. Style the link with buttonVariants instead.",
    },
    schema: [],
  },
  create(context) {
    return {
      JSXOpeningElement(node) {
        if (!buttonNames.has(elementName(node))) {
          return;
        }
        if (isInsideLink(node)) {
          context.report({ node, messageId: "nested" });
        }
        for (const attribute of node.attributes) {
          if (
            attribute.type === "JSXAttribute" &&
            attribute.name.name === "render" &&
            rendersLink(attribute.value?.expression)
          ) {
            context.report({ node: attribute, messageId: "render" });
          }
        }
      },
    };
  },
};

const uiComposition = {
  meta: { name: "ui-composition" },
  rules: { "no-button-in-link": noButtonInLink },
};

export default uiComposition;
