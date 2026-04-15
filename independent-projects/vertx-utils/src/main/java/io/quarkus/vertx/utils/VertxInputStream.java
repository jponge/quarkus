package io.quarkus.vertx.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.impl.ConnectionBase;

/**
 * A blocking {@link InputStream} that reads from a Vert.x {@link HttpServerRequest}.
 * <p>
 * Consumer-specific behavior (continue-state management, max request size, timeout) is
 * provided via a {@link VertxInputContext}.
 */
public class VertxInputStream extends InputStream {

    private final VertxInputContext inputContext;
    private final HttpServerRequest request;
    private final VertxBlockingInput exchange;
    private final long limit;

    private boolean closed;
    private boolean finished;
    private ByteBuf pooled;

    public VertxInputStream(VertxInputContext context) {
        this.inputContext = context;
        this.request = context.getRoutingContext().request();
        this.limit = context.getMaxRequestSize();
        final ConnectionBase connection = (ConnectionBase) request.connection();
        if (!connection.channel().isOpen()) {
            this.exchange = null;
            this.finished = true;
        } else if (request.isEnded()) {
            this.exchange = null;
            this.finished = true;
        } else {
            this.exchange = new VertxBlockingInput(
                    request,
                    context.getTimeout(),
                    () -> request.connection().close(),
                    () -> new BlockingOperationNotAllowedException(
                            "Attempting a blocking read on io thread"));
        }
    }

    public VertxInputStream(VertxInputContext context, ByteBuf existing) {
        this(context);
        this.pooled = existing;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int read = read(b);
        if (read == -1) {
            return -1;
        }
        return b[0] & 0xff;
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (len == 0) {
            return 0;
        }
        if (inputContext.getContinueState() == VertxInputContext.ContinueState.REQUIRED) {
            inputContext.setContinueState(VertxInputContext.ContinueState.SENT);
            request.response().writeContinue();
        }
        readIntoBuffer();
        if (limit > 0 && request.bytesRead() > limit) {
            HttpServerResponse response = request.response();
            if (response.headWritten()) {
                request.connection().close();
                throw new IOException("Request too large");
            } else {
                response.setStatusCode(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code());
                response.headers().add(HttpHeaderNames.CONNECTION, "close");
                response.endHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        request.connection().close();
                    }
                });
                response.end();
                throw new IOException("Request too large");
            }
        }
        if (finished) {
            return -1;
        }
        ByteBuf buffer = pooled;
        int copied = Math.min(len, buffer.readableBytes());
        buffer.readBytes(b, off, copied);
        if (!buffer.isReadable()) {
            pooled.release();
            pooled = null;
        }
        return copied;
    }

    private void readIntoBuffer() throws IOException {
        if (pooled == null && !finished) {
            pooled = exchange.readBlocking();
            if (pooled == null) {
                finished = true;
                pooled = null;
            }
        }
    }

    @Override
    public int available() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (finished) {
            return 0;
        }

        int buffered = exchange.readBytesAvailable();
        if (buffered > 0) {
            return buffered;
        }

        String length = request.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (length == null) {
            return 0;
        }
        try {
            return Integer.parseInt(length);
        } catch (NumberFormatException e) {
            try {
                Long.parseLong(length);
            } catch (NumberFormatException ne) {
                return 0;
            }
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            while (!finished) {
                readIntoBuffer();
                if (pooled != null) {
                    pooled.release();
                    pooled = null;
                }
            }
        } finally {
            if (pooled != null) {
                pooled.release();
                pooled = null;
            }
            finished = true;
        }
    }
}
