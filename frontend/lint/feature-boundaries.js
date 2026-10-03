// ponytail: 動的な境界規則はこの一本だけと想定する。
// アプリやパッケージに分かれたとき、または動的な規則が三本を超えたときは dependency-cruiser へ移る。
// Oxlint の JS plugin API は alpha である。
// alias の `@/features/<name>` だけを見る。`../` は import/no-relative-parent-imports が禁じている。
const featurePattern = /^(?:.*\/)?src\/features\/(?<name>[^/]+)\//u;
const importPattern = /^@\/features\/(?<name>[^/]+)/u;

function featureName(pattern, path) {
  return String(pattern.exec(String(path).replaceAll("\\", "/"))?.groups?.name ?? "");
}

export const noCrossFeatureImport = {
  meta: {
    messages: {
      forbidden:
        "features/{{source}} must not import features/{{target}}. Compose them in a route.",
    },
    schema: [],
  },
  create(context) {
    const source = featureName(featurePattern, context.filename);
    if (source === "") {
      return {};
    }
    const check = (node) => {
      const target = featureName(importPattern, node.source?.value ?? "");
      if (target !== "" && target !== source) {
        context.report({ node: node.source, messageId: "forbidden", data: { source, target } });
      }
    };
    return {
      ImportDeclaration: check,
      ExportNamedDeclaration: check,
      ExportAllDeclaration: check,
      ImportExpression: check,
    };
  },
};

const featureBoundaries = {
  meta: { name: "feature-boundaries" },
  rules: { "no-cross-feature-import": noCrossFeatureImport },
};

export default featureBoundaries;
