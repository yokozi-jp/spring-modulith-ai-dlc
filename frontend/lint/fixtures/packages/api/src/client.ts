export function loadOrders() {
  return fetch("/api/orders");
}

export function createRequest() {
  return new XMLHttpRequest();
}
