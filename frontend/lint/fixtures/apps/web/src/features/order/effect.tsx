import React, { useEffect, useLayoutEffect as layout, useEffectEvent } from "react";

export { useInsertionEffect as useMount } from "react";

const { useEffect: pulled } = React;

export function Effects() {
  useEffect(() => {}, []);
  layout(() => {}, []);
  React.useInsertionEffect(() => {}, []);
  pulled(() => {}, []);
  useEffectEvent(() => {});
  return null;
}
