package io.quarkus.vertx.utils;

/**
 * Thrown when a blocking I/O operation is attempted on the Vert.x event loop thread.
 */
public class BlockingOperationNotAllowedException extends RuntimeException {

    public BlockingOperationNotAllowedException(String message) {
        super(message);
    }
}
