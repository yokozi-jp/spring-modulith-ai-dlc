import { useForm } from "@tanstack/react-form";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { cva } from "class-variance-authority";
import { createInstance } from "i18next";
import { Temporal } from "temporal-polyfill";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { useTranslation } from "react-i18next";
import { describe, expect, it } from "vite-plus/test";

import { ApiProblemError } from "@frontend/api/api-fetch";

describe("import を多く持つ注文", () => {
  it("すべての import を使う", () => {
    expect([
      useForm,
      useQuery,
      Link,
      cva,
      createInstance,
      Temporal,
      useState,
      createRoot,
      useTranslation,
      ApiProblemError,
    ]).not.toContain(undefined);
  });
});
