import { useHref } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { useOrderDraft } from "@/features/order/hooks/use-order-draft";

export const reactInModule = { useHref, useOrderDraft, useState, useTranslation };
