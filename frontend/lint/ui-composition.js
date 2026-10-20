// ponytail: 要素を名前（Link、a、Button、button）だけで判定する。別名で import した component は見逃す。
// 見逃しが起きたら、import の解決元（@tanstack/react-router、@/components/ui/button）で判定する規則に移す。
// Oxlint の JS plugin API は alpha である。
const linkNames = new Set(["Link", "a"]);
const buttonNames = new Set(["Button", "button"]);

function elementName(openingElement) {
  return openingElement?.name?.type === "JSXIdentifier" ? String(openingElement.name.name) : "";
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
          const expression = attribute.value?.expression;
          if (
            attribute.type === "JSXAttribute" &&
            attribute.name.name === "render" &&
            expression?.type === "JSXElement" &&
            linkNames.has(elementName(expression.openingElement))
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
