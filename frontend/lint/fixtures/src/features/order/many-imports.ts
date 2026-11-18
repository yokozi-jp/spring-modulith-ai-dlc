import { useForm } from "@tanstack/react-form";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { cva } from "class-variance-authority";
import { createInstance } from "i18next";
import { Temporal } from "temporal-polyfill";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { useTranslation } from "react-i18next";
import { z } from "zod";

import { orderPath } from "@/api/generated/order";

export const dependencies = [
  useForm,
  useQuery,
  Link,
  cva,
  createInstance,
  Temporal,
  useState,
  createRoot,
  useTranslation,
  z,
  orderPath,
];
