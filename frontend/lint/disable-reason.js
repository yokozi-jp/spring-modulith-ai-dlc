const disableDirective = /^\s*(?:oxlint|eslint)-disable(?:-next-line|-line)?(?=\s|$)/u;
const reason = /\s--\s+\S/u;

// ponytail: OxlintにもESLintにも、無効化コメントの理由を求める組み込みの規則がない。組み込みができたら置き換える。
export const requireDisableReason = {
  meta: {
    messages: {
      missing:
        "Add the reason on the same line after the rule name, like `// oxlint-disable-next-line rule-name -- <reason>`.",
    },
    schema: [],
  },
  create(context) {
    return {
      Program() {
        /** @type {{ value: string }[]} */
        const comments = context.sourceCode.getAllComments();
        for (const comment of comments) {
          if (disableDirective.test(comment.value) && !reason.test(comment.value)) {
            context.report({ node: comment, messageId: "missing" });
          }
        }
      },
    };
  },
};

const disableReason = {
  meta: { name: "disable-reason" },
  rules: { "require-disable-reason": requireDisableReason },
};

export default disableReason;
