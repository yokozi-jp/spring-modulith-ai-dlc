export const loadByFetch = () => fetch("/api/orders");
export const loadByGlobalThis = () => globalThis.fetch("/api/orders");
export const loadByWindow = () => window.fetch("/api/orders");
export const createRequest = () => new XMLHttpRequest();
