// oxlint-disable max-lines -- lint と fmt の設定を 1 つの defineConfig に集める正本のため、行数で分割しない。
import { defineConfig } from "vite-plus";

const restrictedHtmlProperties = [
  "innerHTML",
  "outerHTML",
  "insertAdjacentHTML",
  "createContextualFragment",
  "setHTMLUnsafe",
  "parseHTMLUnsafe",
  "DOMParser",
  "srcdoc",
].map((property) => ({ property, message: "Use React children or textContent instead." }));

// override の option は前の指定とマージされず置き換わるため、禁止の一覧を定数に分けて override ごとに組み合わせる。
const htmlSinkGlobals = [{ name: "DOMParser", message: "Do not parse arbitrary HTML." }];
const apiMessage = "Use the generated API client from @frontend/api.";
const japaneseTitle = String.raw`[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]`;

const mockMessage = "Use MSW, vi.spyOn or vi.stubGlobal instead of module mocks.";
const waitMessage =
  "Use findBy or waitFor from Testing Library, expect.poll, or createGate from src/testing/gate.ts.";
const networkGlobals = ["fetch", "XMLHttpRequest"].map((name) => ({ name, message: apiMessage }));
const htmlSinkProperties = [
  ...restrictedHtmlProperties,
  { object: "document", property: "write", message: "Do not write HTML directly." },
  { object: "document", property: "writeln", message: "Do not write HTML directly." },
];
const networkProperties = [
  { object: "window", property: "fetch", message: apiMessage },
  { object: "globalThis", property: "fetch", message: apiMessage },
];
const sharedLayerImports = {
  group: ["@/features/**", "@/routes/**", "@/routeTree.gen"],
  message: "Shared code must not depend on features or routes.",
};
// lib は最下層であり、共有層の禁止に加えて components も参照しない。
const libLayerImports = {
  group: [...sharedLayerImports.group, "@/components/**"],
  message: "lib must not depend on components, features or routes.",
};
// components が使える @frontend/api の入口は api-error と csrf だけである。送信とデータの取得は route と feature が行う。
const componentLayerImports = {
  group: [
    "@frontend/api/api-fetch",
    "@frontend/api/endpoints/**",
    "@frontend/api/models",
    "@frontend/api/zod/**",
  ],
  message: "components may use only @frontend/api/api-error and @frontend/api/csrf.",
};
const featureImports = {
  group: ["@/routes/**", "@/routeTree.gen"],
  message: "Use getRouteApi instead of importing a route.",
};
// 同じ feature の画面、component、Hook は、alias と、feature の直下の ./ の両方で禁じる。../ は import/no-relative-parent-imports が禁じる。
const pageGlobs = ["@/features/*/pages/**", "./pages/**"];
const componentGlobs = ["@/features/*/components/**", "./components/**"];
const hookGlobs = ["@/features/*/hooks/**", "./hooks/**"];
const featureUiImports = {
  group: [...pageGlobs, ...componentGlobs, ...hookGlobs],
  message: "Do not depend on the feature's pages, components or Hooks from a module.",
};
const reactImports = {
  group: ["react", "react/**", "react-dom", "react-dom/**", "react-i18next"],
  message: "Do not use React here (docs/frontend/file-roles/feature-module.md).",
};
// ponytail: React に依存する package を名前で列挙する。新しい React 依存の package を足したら、ここにも足す。
const reactLibraryImports = {
  group: ["@tanstack/react-*", "@frontend/ui/**", "lucide-react"],
  message:
    "A feature module must not use React libraries (docs/frontend/file-roles/feature-module.md).",
};
const hookImports = {
  group: [...pageGlobs, ...componentGlobs, "@/components/**"],
  message: "A feature Hook must not depend on pages or components.",
};
const baseUiImports = {
  group: ["@base-ui/**"],
  message: "Import UI primitives from @frontend/ui (ADR-025).",
};
const testOnlyImports = {
  group: ["msw", "msw/**", "@frontend/api/mocks/**", "@/testing/**", "@testing-library/**"],
  message: "Use test-only modules only in test files and src/testing.",
};
// no-restricted-imports は静的な import に加えて import("@grafana/...") も検出する（lint/fixtures の telemetry-sdk.ts で確かめている）。
const telemetrySdkImports = {
  group: ["@grafana/*"],
  message: "Call the telemetry SDK only through src/lib/telemetry.ts (ADR-068).",
};

const featurePatterns = [featureImports, baseUiImports, testOnlyImports, telemetrySdkImports];

// better-tailwindcss の規則。entryPoint を option で渡すため、規則の一覧をここに持つ。
const tailwindRuleNames = [
  "enforce-consistent-class-order",
  "no-deprecated-classes",
  "no-duplicate-classes",
  "no-unnecessary-whitespace",
  "no-conflicting-classes",
  "enforce-canonical-classes",
];

// entryPoint の相対パスは cwd 基準で、vp run -r は package の dir で走るため解決しない。呼び出し側は設定ファイルの場所から作った絶対パスを渡す（ADR-092）。
function tailwindRules(entryPoint: string) {
  return Object.fromEntries(
    tailwindRuleNames.map((name): [string, ["error", { entryPoint: string }]] => [
      `better-tailwindcss/${name}`,
      ["error", { entryPoint }],
    ]),
  );
}

// 生成物。整形と静的解析から除外する。
const generatedFiles = ["apps/*/src/routeTree.gen.ts", "packages/api/src/generated/**"];
// lint 設定のテストが使う、違反を含む fixture。通常の整形、静的解析から外す。
const lintFixtures = ["lint/fixtures/**"];

export default defineConfig({
  fmt: { ignorePatterns: [...generatedFiles, ...lintFixtures], sortImports: true },
  lint: {
    ignorePatterns: [...generatedFiles, ...lintFixtures],
    categories: {
      correctness: "error",
      suspicious: "error",
      pedantic: "error",
      perf: "error",
      style: "error",
      restriction: "error",
    },
    plugins: [
      "eslint",
      "unicorn",
      "typescript",
      "oxc",
      "react",
      "import",
      "vitest",
      "jsx-a11y",
      "promise",
    ],
    jsPlugins: [
      { name: "vite-plus", specifier: "vite-plus/oxlint-plugin" },
      { name: "local-security", specifier: "./lint/local-security.js" },
      { name: "disable-reason", specifier: "./lint/disable-reason.js" },
      { name: "shadcn", specifier: "@shadcn/lint" },
      { name: "better-tailwindcss", specifier: "eslint-plugin-better-tailwindcss" },
      { name: "tailwind-classes", specifier: "./lint/tailwind-classes.js" },
      { name: "feature-boundaries", specifier: "./lint/feature-boundaries.js" },
      { name: "ui-composition", specifier: "./lint/ui-composition.js" },
      { name: "react-rules", specifier: "./lint/react-rules.js" },
      { name: "testing-library", specifier: "eslint-plugin-testing-library" },
    ],
    rules: {
      "react/react-in-jsx-scope": "off",
      "react/forbid-component-props": "off",
      "react/jsx-filename-extension": ["error", { extensions: [".jsx", ".tsx"] }],
      "react/jsx-max-depth": "off",
      "react/jsx-props-no-spreading": "off",
      "import/no-default-export": "off",
      "import/no-named-export": "off",
      "import/prefer-default-export": "off",
      "import/exports-last": "off",
      "import/group-exports": "off",
      "import/no-unassigned-import": ["error", { allow: ["**/*.css"] }],
      "oxc/no-async-await": "off",
      "oxc/no-optional-chaining": "off",
      "oxc/no-rest-spread-properties": "off",
      "no-void": ["error", { allowAsStatement: true }],
      // settings の jsx-a11y.components で Link を a に対応づけた組。
      "jsx-a11y/anchor-is-valid": ["error", { specialLink: ["to"] }],
      "no-undefined": "off",
      "typescript/explicit-function-return-type": "off",
      "typescript/explicit-module-boundary-types": "off",
      "typescript/prefer-readonly-parameter-types": "off",
      "typescript/promise-function-async": "off",
      "capitalized-comments": "off",
      "func-style": ["error", "declaration"],
      "id-length": ["error", { exceptions: ["t"] }],
      "no-underscore-dangle": ["error", { allow: ["__TELEMETRY_ENABLED__", "__TELEMETRY_APP__"] }],
      "max-lines-per-function": "off",
      "no-duplicate-imports": ["error", { allowSeparateTypeImports: true }],
      "no-magic-numbers": "off",
      "no-ternary": "off",
      "one-var": "off",
      "sort-imports": "off",
      "sort-keys": "off",
      "vitest/no-conditional-in-test": "off",
      "vitest/no-hooks": "off",
      "vitest/no-importing-vitest-globals": "off",
      "vitest/prefer-called-times": "off",
      "vitest/prefer-describe-function-title": "off",
      "vitest/prefer-expect-assertions": "off",
      "vitest/prefer-lowercase-title": "off",
      "vitest/prefer-strict-boolean-matchers": "off",
      "vitest/prefer-to-be-truthy": "off",
      "vitest/require-hook": "off",
      "vitest/require-test-timeout": "off",
      "vite-plus/prefer-vite-plus-imports": "error",
      "react/no-danger": "error",
      "vitest/no-restricted-vi-methods": [
        "error",
        { mock: mockMessage, doMock: mockMessage, waitFor: waitMessage, waitUntil: waitMessage },
      ],
      "no-restricted-imports": [
        "error",
        { patterns: [baseUiImports, testOnlyImports, telemetrySdkImports] },
      ],
      "no-restricted-globals": ["error", ...htmlSinkGlobals, ...networkGlobals],
      "no-restricted-properties": ["error", ...htmlSinkProperties, ...networkProperties],
      "local-security/no-jsx-srcdoc": "error",
      "disable-reason/require-disable-reason": "error",
      "feature-boundaries/no-cross-feature-import": "error",
      "ui-composition/no-button-in-link": "error",
      "react-rules/no-effect": "error",
      // テスト名は日本語で書く。describe だけは、空白を含まない 1 語のコードの名前も許す（docs/frontend/testing.md）。
      "vitest/valid-title": [
        "error",
        {
          mustMatch: {
            it: japaneseTitle,
            test: japaneseTitle,
            describe: `${japaneseTitle}|^\\S+$`,
          },
        },
      ],
      "shadcn/no-restyle": ["error", { allow: ["layout"] }],
      "shadcn/no-raw-colors": "error",
      "shadcn/no-arbitrary-values": ["error", { allow: ["layout"] }],
      "shadcn/no-inline-styles": "error",
      "shadcn/require-static-classes": "error",
      "shadcn/no-unknown-classes": "error",
      "tailwind-classes/no-dynamic-class-name": "error",
    },
    // 同じ rule を指定する override は、後に一致したものの option だけが効く。
    // 正しさは override の並び順と、option が置き換わることに依存する。並べ替えるときは lint/lint-config.test.js で確かめる。
    overrides: [
      // アプリごとの CSS を指す（ADR-092）。
      {
        files: ["apps/web/**"],
        rules: tailwindRules(`${import.meta.dirname}/apps/web/src/style.css`),
      },
      {
        files: ["apps/*/src/**/*.test.{ts,tsx}", "apps/*/src/testing/**"],
        rules: {
          "testing-library/await-async-queries": "error",
          "testing-library/await-async-utils": "error",
          "testing-library/no-container": "error",
          "testing-library/no-manual-cleanup": "error",
          "testing-library/no-node-access": "error",
          "testing-library/no-unnecessary-act": "error",
          "testing-library/no-wait-for-multiple-assertions": "error",
          "testing-library/no-wait-for-side-effects": "error",
          "testing-library/prefer-find-by": "error",
          "testing-library/prefer-screen-queries": "error",
          "testing-library/prefer-user-event": "error",
        },
      },
      // Vitest は globals を渡さないので、Testing Library の自動 cleanup が効かない。setup.ts の cleanup() が唯一の後片付けである。
      {
        files: ["apps/*/src/testing/setup.ts"],
        rules: { "testing-library/no-manual-cleanup": "off" },
      },
      {
        files: ["lint/**"],
        env: { node: true },
        rules: {
          "no-undef": "error",
          "import/no-nodejs-modules": "off",
          "new-cap": "off",
          "typescript/no-unsafe-assignment": "off",
          "typescript/no-unsafe-call": "off",
          "typescript/no-unsafe-member-access": "off",
        },
      },
      {
        files: ["apps/*/src/{api,i18n}/**"],
        rules: {
          "no-restricted-imports": [
            "error",
            {
              patterns: [sharedLayerImports, baseUiImports, testOnlyImports, telemetrySdkImports],
            },
          ],
        },
      },
      {
        files: ["apps/*/src/components/**"],
        rules: {
          "no-restricted-imports": [
            "error",
            {
              patterns: [
                sharedLayerImports,
                componentLayerImports,
                baseUiImports,
                testOnlyImports,
                telemetrySdkImports,
              ],
            },
          ],
        },
      },
      {
        files: ["apps/*/src/lib/**"],
        rules: {
          "no-restricted-imports": [
            "error",
            {
              patterns: [libLayerImports, baseUiImports, testOnlyImports, telemetrySdkImports],
            },
          ],
        },
      },
      // テレメトリの SDK を呼ぶ唯一のファイル（ADR-068）。
      {
        files: ["apps/*/src/lib/telemetry.ts"],
        rules: {
          "no-restricted-imports": [
            "error",
            { patterns: [libLayerImports, baseUiImports, testOnlyImports] },
          ],
        },
      },
      {
        files: ["apps/*/src/features/**"],
        rules: {
          "no-restricted-imports": ["error", { patterns: featurePatterns }],
          // custom Hook は hooks/ にだけ書く（下の override が hooks/ だけで外す）。
          "react-rules/no-hook-definition": "error",
        },
      },
      {
        files: ["apps/*/src/features/*/hooks/**"],
        rules: {
          "react-rules/no-hook-definition": "off",
          "no-restricted-imports": ["error", { patterns: [...featurePatterns, hookImports] }],
        },
      },
      // feature の直下の .ts は module で、React を使わない。テストは、後ろのテストの override が制限を置き換える。
      {
        files: ["apps/*/src/features/*/*.ts"],
        rules: {
          "no-restricted-imports": [
            "error",
            { patterns: [...featurePatterns, reactImports, reactLibraryImports, featureUiImports] },
          ],
        },
      },
      // form.ts は @tanstack/react-form、queries.ts は @tanstack/react-query を使う。React 本体と画面、component、Hook には依存しない。
      {
        files: ["apps/*/src/features/*/{form,queries}.ts"],
        rules: {
          "no-restricted-imports": [
            "error",
            { patterns: [...featurePatterns, reactImports, featureUiImports] },
          ],
        },
      },
      // Base UI を import できる唯一の package（ADR-025、ADR-092）。ui は api とアプリを package.json に書かないので、層の import 制限は要らない。
      {
        files: ["packages/ui/**"],
        rules: {
          ...tailwindRules(`${import.meta.dirname}/packages/ui/src/style.css`),
          "shadcn/no-restyle": "off",
          "shadcn/no-arbitrary-values": "off",
          "shadcn/require-static-classes": "off",
          "no-restricted-imports": ["error", { patterns: [testOnlyImports, telemetrySdkImports] }],
          "react/only-export-components": "off",
        },
      },
      // fetch と XMLHttpRequest を使える唯一の package（ADR-092）。
      {
        files: ["packages/api/**"],
        rules: {
          "no-restricted-imports": [
            "error",
            { patterns: [baseUiImports, testOnlyImports, telemetrySdkImports] },
          ],
          "no-restricted-globals": ["error", ...htmlSinkGlobals],
          "no-restricted-properties": ["error", ...htmlSinkProperties],
        },
      },
      {
        files: ["apps/*/src/routes/**"],
        rules: {
          "react/only-export-components": "off",
        },
      },
      {
        files: ["**/*.{test,spec}.{ts,tsx,js,jsx}"],
        rules: {
          "react/jsx-no-literals": "off",
          "import/max-dependencies": "off",
        },
      },
      // Playwright Test の API に vitest plugin の規則が当たるため。
      {
        files: ["apps/*/e2e/**"],
        rules: {
          "vitest/consistent-test-filename": "off",
          "vitest/prefer-importing-vitest-globals": "off",
        },
      },
      // ponytail: option が置き換わるため、テストでは層の import 制限も外れる。
      // テストにも層の制限が要るようになったら dependency-cruiser へ移る。
      {
        files: [
          "**/*.{test,spec}.{ts,tsx,js,jsx}",
          "apps/*/src/testing/**",
          "packages/*/src/testing/**",
        ],
        rules: {
          "no-restricted-imports": ["error", { patterns: [baseUiImports, telemetrySdkImports] }],
          // renderHook の wrapper など、テストの補助の Hook を許す。
          "react-rules/no-hook-definition": "off",
        },
      },
    ],
    settings: {
      // shadcn の部品を別 package の @frontend/ui から import するため、components.json の alias に加えて import 元でも部品と認識させる（ADR-092）。
      shadcn: { componentImports: ["^@frontend/ui(/|$)"] },
      // JSX の名前から要素への写像。docs/frontend/lint-config.md の「jsx-a11y を部品へ効かせる」を参照。
      "jsx-a11y": { components: { Link: "a", Button: "button" } },
    },
    options: {
      denyWarnings: true,
      reportUnusedDisableDirectives: "error",
      typeAware: true,
      typeCheck: true,
    },
  },
});
