import { describe, expect, it, vi } from "vite-plus/test";
import { noJsxSrcDoc } from "./local-security.js";

function checkAttribute(name) {
  const report = vi.fn();
  const visitor = noJsxSrcDoc.create({ report });

  visitor.JSXAttribute({ name: { type: "JSXIdentifier", name } });

  return report;
}

describe("local-security/no-jsx-srcdoc", () => {
  it("rejects srcDoc", () => {
    expect(checkAttribute("srcDoc")).toHaveBeenCalledOnce();
  });

  it("allows ordinary JSX attributes", () => {
    expect(checkAttribute("src")).not.toHaveBeenCalled();
  });
});
