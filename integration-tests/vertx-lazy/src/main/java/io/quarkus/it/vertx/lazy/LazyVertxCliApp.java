package io.quarkus.it.vertx.lazy;

import jakarta.inject.Inject;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpServer;

@QuarkusMain
public class LazyVertxCliApp implements QuarkusApplication {

    @Inject
    Vertx vertx;

    @Override
    public int run(String... args) throws Exception {
        if (hasVertxEventLoopThreads()) {
            System.out.println("FAIL: event loop threads exist before first Vertx use");
            return 1;
        }
        System.out.println("PHASE1_OK: no event loop threads before Vertx use");

        HttpServer server = vertx.createHttpServer()
                .requestHandler(req -> req.response().endAndForget("OK"))
                .listenAndAwait(0);
        int port = server.actualPort();

        if (!hasVertxEventLoopThreads()) {
            System.out.println("FAIL: no event loop threads after creating HTTP server");
            return 2;
        }
        System.out.println("PHASE2_OK: event loop threads exist after Vertx use");

        String body = vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.GET, port, "localhost", "/")
                .flatMap(req -> req.send())
                .flatMap(resp -> resp.body())
                .map(buf -> buf.toString())
                .await().indefinitely();

        if (!"OK".equals(body)) {
            System.out.println("FAIL: unexpected response body: " + body);
            return 3;
        }
        System.out.println("PHASE3_OK: HTTP server responded correctly");

        server.closeAndAwait();
        return 0;
    }

    private boolean hasVertxEventLoopThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("vert.x-eventloop-thread"));
    }
}
