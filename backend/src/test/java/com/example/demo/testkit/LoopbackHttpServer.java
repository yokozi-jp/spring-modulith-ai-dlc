package com.example.demo.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * テストの中で外部システムの代わりに応答する、ループバックの空いているポートの HTTP サーバ。
 *
 * <p>JDK の {@link HttpServer} を包む。応答はパスごとに {@link #handle} で足し、テストの後に {@link #stop} で止める。
 */
public final class LoopbackHttpServer {

  /** 包む JDK の HTTP サーバ。 */
  private final HttpServer server;

  private LoopbackHttpServer(final HttpServer server) {
    this.server = server;
  }

  /** ループバックの空いているポートで起動する。 */
  public static LoopbackHttpServer start() throws IOException {
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.start();
    return new LoopbackHttpServer(server);
  }

  /** パスとその下への要求に、handler で応答する。 */
  public LoopbackHttpServer handle(final String path, final HttpHandler handler) {
    server.createContext(path, handler);
    return this;
  }

  /** サーバの URL（{@code http://127.0.0.1:<port>}）。末尾に {@code /} を付けない。 */
  public String url() {
    final InetSocketAddress address = server.getAddress();
    try {
      // IPv6 のループバックでも角括弧で囲むよう、host と port から組み立てる。
      return new URI(
              "http",
              null,
              address.getAddress().getHostAddress(),
              address.getPort(),
              null,
              null,
              null)
          .toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("ループバックの URL を組み立てられない: address=" + address, e);
    }
  }

  /** JSON の本文で応答する。本文が空なら本文を送らない。 */
  public static void reply(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** サーバを止める。 */
  public void stop() {
    server.stop(0);
  }
}
