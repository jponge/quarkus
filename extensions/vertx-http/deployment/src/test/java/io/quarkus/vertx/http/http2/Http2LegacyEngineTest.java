package io.quarkus.vertx.http.http2;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

// Copies Http2Test, to be removed the day the old Vert.x HTTP/2 engine is removed
class Http2LegacyEngineTest {

    private static final String PING_DATA = "12345678";

    @TestHTTPResource(value = "/ping")
    URL plainUrl;

    @Inject
    Vertx vertx;

    private WebClient client;
    private HttpClient httpClient;

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Http2Test.MyBean.class))
            .overrideConfigKey("quarkus.vertx.use-http2-multiplex-implementation", "false");

    @AfterEach
    public void closeClient() throws TimeoutException {
        if (client != null) {
            httpClient.close().await(5, TimeUnit.SECONDS);
            httpClient = null;
            client = null;
        }
    }

    @Test
    void testHttp2WithLegacyEngine() throws ExecutionException, InterruptedException {
        WebClientOptions options = new WebClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(true);
        httpClient = vertx.createHttpClient(options);
        client = WebClient.wrap(httpClient, options);

        CompletableFuture<HttpResponse<Buffer>> result = new CompletableFuture<>();
        client.get(plainUrl.getPort(), "localhost", "/ping")
                .send().onComplete(ar -> {
                    if (ar.succeeded()) {
                        result.complete(ar.result());
                    } else {
                        result.completeExceptionally(ar.cause());
                    }
                });

        HttpResponse<Buffer> response = result.get();
        Assertions.assertEquals(HttpVersion.HTTP_2, response.version());
        Assertions.assertEquals(PING_DATA, response.bodyAsString());
    }
}
