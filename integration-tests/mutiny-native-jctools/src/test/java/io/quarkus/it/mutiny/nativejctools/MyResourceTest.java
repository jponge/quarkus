package io.quarkus.it.mutiny.nativejctools;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class MyResourceTest {

    @Test
    public void testCreateQueues() {
        get("/tests/create-queues")
                .then()
                .body(is("Ok :: 523776/523776/523776/523776/523776/523776"))
                .statusCode(200);
    }
}
