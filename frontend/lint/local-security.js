// ponytail: OxlintのJS plugin APIはalpha。組み込みのJSX属性制限が追加されたら置き換える。
export const noJsxSrcDoc = {
  meta: {
    messages: { forbidden: "Use an iframe URL instead of rendering arbitrary HTML with srcDoc." },
    schema: [],
  },
  create(context) {
    return {
      JSXAttribute(node) {
        if (node.name.type === "JSXIdentifier" && node.name.name === "srcDoc") {
          context.report({ node, messageId: "forbidden" });
        }
      },
    };
  },
};

const localSecurity = {
  meta: { name: "local-security" },
  rules: { "no-jsx-srcdoc": noJsxSrcDoc },
};

export default localSecurity;
