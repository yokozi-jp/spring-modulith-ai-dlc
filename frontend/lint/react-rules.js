// ponytail: react から import した名前と、その default import の名前（React など）だけを追う。
// 自前の関数で包んだ Effect は見逃す。見逃しが起きたら、型情報を使う規則に移す。
// Oxlint の JS plugin API は alpha である。
const effectHooks = new Set([
  "useEffect",
  "useLayoutEffect",
  "useInsertionEffect",
  "useEffectEvent",
]);

const message =
  "Do not use Effects (ADR-088). Compute in render, handle in an event handler, use a ref callback, or useSyncExternalStore.";

function isReactSource(node) {
  return node?.source?.value === "react";
}

function nameOf(node) {
  return String(node?.name ?? node?.value);
}

export const noEffect = {
  meta: { messages: { effect: message }, schema: [] },
  create(context) {
    const reactBindings = new Set();
    function isReactBinding(node) {
      return node?.type === "Identifier" && reactBindings.has(node.name);
    }
    return {
      ImportDefaultSpecifier(node) {
        if (isReactSource(node.parent)) {
          reactBindings.add(node.local.name);
        }
      },
      ImportSpecifier(node) {
        if (isReactSource(node.parent) && effectHooks.has(nameOf(node.imported))) {
          context.report({ node, messageId: "effect" });
        }
      },
      ExportSpecifier(node) {
        if (isReactSource(node.parent) && effectHooks.has(nameOf(node.local))) {
          context.report({ node, messageId: "effect" });
        }
      },
      MemberExpression(node) {
        if (
          isReactBinding(node.object) &&
          node.computed !== true &&
          effectHooks.has(nameOf(node.property))
        ) {
          context.report({ node, messageId: "effect" });
        }
      },
      VariableDeclarator(node) {
        if (!isReactBinding(node.init) || node.id?.type !== "ObjectPattern") {
          return;
        }
        for (const property of node.id.properties) {
          if (property.type === "Property" && effectHooks.has(nameOf(property.key))) {
            context.report({ node: property, messageId: "effect" });
          }
        }
      },
    };
  },
};

// func-style が宣言以外の関数を禁じるため、Hook の定義は関数宣言だけを見る。
const hookName = /^use[A-Z]/u;

export const noHookDefinition = {
  meta: {
    messages: {
      hook: "Define custom Hooks only in features/<feature>/hooks/use-<name>.ts (docs/frontend/file-roles/feature-hook.md). Move this function there and import it.",
    },
    schema: [],
  },
  create(context) {
    return {
      FunctionDeclaration(node) {
        if (hookName.test(nameOf(node.id))) {
          context.report({ node: node.id, messageId: "hook" });
        }
      },
    };
  },
};

const reactRules = {
  meta: { name: "react-rules" },
  rules: { "no-effect": noEffect, "no-hook-definition": noHookDefinition },
};

export default reactRules;
