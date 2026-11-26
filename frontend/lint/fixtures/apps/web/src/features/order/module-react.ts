import { Plus } from "lucide-react";
import { jsx } from "react/jsx-runtime";
import { createRoot } from "react-dom/client";
import { useState } from "react";
import { buttonVariants } from "@frontend/ui/button";
import { useQueryClient } from "@tanstack/react-query";
import { useHref } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { render } from "react-dom";

import { OrderRow as AliasComponent } from "@/features/order/components/order-row";
import { useOrderDraft as aliasHook } from "@/features/order/hooks/use-order-draft";
import { OrderPage as AliasPage } from "@/features/order/pages/order-page";
import { OrderRow } from "./components/order-row";
import { useOrderDraft } from "./hooks/use-order-draft";
import { OrderPage } from "./pages/order-page";

export const reactInModule = {
  AliasComponent,
  AliasPage,
  OrderPage,
  OrderRow,
  Plus,
  aliasHook,
  buttonVariants,
  createRoot,
  jsx,
  render,
  useHref,
  useOrderDraft,
  useQueryClient,
  useState,
  useTranslation,
};
