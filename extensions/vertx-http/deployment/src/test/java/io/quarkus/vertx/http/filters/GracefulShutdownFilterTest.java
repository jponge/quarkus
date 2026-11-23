package io.quarkus.vertx.http.filters;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.quarkus.runtime.shutdown.ShutdownRecorder;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;

public class GracefulShutdownFilterTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(MyBean.class, GracefulShutdownFilterTest.class))
            .overrideConfigKey("quarkus.shutdown.timeout", "10");

    @Inject
    Vertx vertx;

    @Test
    public void test() throws URISyntaxException, IOException, InterruptedException, TimeoutException {
        get("/").then().statusCode(200)
                .header(HttpHeaders.CONNECTION.toString(), is(not((HttpHeaderValues.CLOSE.toString()))))
                .body(is("http/1.1"));

        ShutdownRecorder.runShutdown();

        testWithJdkHttpClientAndHttp2AfterShutdown();

        testWithVertxHttpClientAndHttp2GracefulShutdown();

        testWithRestAssuredAndHttp11AfterShutdown();
    }

    private void testWithRestAssuredAndHttp11AfterShutdown() {
        get("/").then().statusCode(200)
                .header(HttpHeaders.CONNECTION.toString(), is(HttpHeaderValues.CLOSE.toString()))
                .body(is("http/1.1"));
    }

    private void testWithVertxHttpClientAndHttp2GracefulShutdown() throws TimeoutException {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setDefaultHost("localhost")
                .setDefaultPort(RestAssured.port));

        // Per RFC 9113 §6.8, streams ≤ lastStreamId may complete during graceful shutdown.
        // The server may close the connection before the client consumes the response, which is fine.
        Future<String> response = client
                .request(HttpMethod.GET, "/")
                .compose(HttpClientRequest::send)
                .compose(resp -> {
                    Assertions.assertThat(resp.statusCode()).isEqualTo(200);
                    return resp.body().map(Buffer::toString);
                });

        try {
            String body = response.await(10, TimeUnit.SECONDS);
            Assertions.assertThat(body).isEqualTo("h2");
        } catch (HttpClosedException e) {
            // Fine: server closed the connection before the client consumed the response.
        }

        client.close().await(5, TimeUnit.SECONDS);
    }

    private void testWithJdkHttpClientAndHttp2AfterShutdown()
            throws URISyntaxException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(RestAssured.baseURI + ":" + RestAssured.port))
                .GET()
                .build();
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertThat(response.headers().firstValue(HttpHeaders.CONNECTION.toString())).isEmpty();
        Assertions.assertThat(response.body()).isEqualTo("h2");
    }

    @ApplicationScoped
    public static class MyBean {

        public void register(@Observes Router router) {
            router
                    .get("/")
                    .handler(rc -> rc.response().end(rc.request().version().alpnName()));
        }

    }

}
