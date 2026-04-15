package io.quarkus.vertx.utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

public class VertxBlockingInput implements Handler<Buffer> {

    private final ReadStream<Buffer> stream;
    private final long timeout;
    private final Runnable onTimeout;
    private final Supplier<RuntimeException> blockingExceptionSupplier;
    private final Object lock = new Object();

    private Buffer inputBuffer;
    private Deque<Buffer> inputOverflow;
    private boolean waiting;
    private boolean eof;
    private Throwable readException;

    /**
     * @param stream the read stream to consume (immediately paused and set up)
     * @param timeout read timeout in milliseconds
     * @param onTimeout called on timeout (e.g. close the connection)
     * @param blockingExceptionSupplier exception to throw if called on the event loop
     */
    public VertxBlockingInput(ReadStream<Buffer> stream, long timeout, Runnable onTimeout,
            Supplier<RuntimeException> blockingExceptionSupplier) {
        this.stream = stream;
        this.timeout = timeout;
        this.onTimeout = onTimeout;
        this.blockingExceptionSupplier = blockingExceptionSupplier;
        synchronized (lock) {
            try {
                stream.pause();
                stream.handler(this);
                stream.endHandler(event -> {
                    synchronized (lock) {
                        eof = true;
                        if (waiting) {
                            lock.notifyAll();
                        }
                    }
                });
                stream.exceptionHandler(event -> {
                    synchronized (lock) {
                        readException = new IOException(event);
                        if (inputBuffer != null) {
                            inputBuffer.getByteBuf().release();
                            inputBuffer = null;
                        }
                        if (inputOverflow != null) {
                            Buffer d = inputOverflow.poll();
                            while (d != null) {
                                d.getByteBuf().release();
                                d = inputOverflow.poll();
                            }
                        }
                        if (waiting) {
                            lock.notifyAll();
                        }
                    }
                });
                stream.fetch(1);
            } catch (IllegalStateException e) {
                eof = true;
            }
        }
    }

    /**
     * @return the next {@link ByteBuf} (caller must release), or {@code null} at EOF
     */
    public ByteBuf readBlocking() throws IOException {
        long expire = System.currentTimeMillis() + timeout;
        synchronized (lock) {
            while (inputBuffer == null && !eof && readException == null) {
                long rem = expire - System.currentTimeMillis();
                if (rem <= 0) {
                    onTimeout.run();
                    IOException throwable = new IOException("Read timed out");
                    readException = throwable;
                    throw throwable;
                }

                try {
                    if (Context.isOnEventLoopThread()) {
                        throw blockingExceptionSupplier.get();
                    }
                    waiting = true;
                    lock.wait(rem);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException(e.getMessage());
                } finally {
                    waiting = false;
                }
            }
            if (readException != null) {
                throw new IOException(readException);
            }
            Buffer ret = inputBuffer;
            inputBuffer = null;
            if (inputOverflow != null) {
                inputBuffer = inputOverflow.poll();
                if (inputBuffer == null) {
                    stream.fetch(1);
                }
            } else if (!eof) {
                stream.fetch(1);
            }
            return (ret == null) ? null : ret.getByteBuf();
        }
    }

    @Override
    public void handle(Buffer event) {
        synchronized (lock) {
            if (event.length() == 0) {
                eof = true;
                if (waiting) {
                    lock.notifyAll();
                }
                return;
            }
            if (inputBuffer == null) {
                inputBuffer = event;
            } else {
                if (inputOverflow == null) {
                    inputOverflow = new ArrayDeque<>();
                }
                inputOverflow.add(event);
            }
            if (waiting) {
                lock.notifyAll();
            }
        }
    }

    /**
     * Returns buffered bytes available without blocking (no Content-Length fallback).
     */
    public int readBytesAvailable() {
        synchronized (lock) {
            if (inputBuffer != null) {
                return inputBuffer.getByteBuf().readableBytes();
            }
        }
        return 0;
    }

    /**
     * Reads and releases all remaining buffers until EOF.
     */
    public void drain() throws IOException {
        while (true) {
            ByteBuf buf = readBlocking();
            if (buf == null) {
                return;
            }
            buf.release();
        }
    }

    /**
     * Detaches all handlers, releases buffered data, and resumes the stream.
     */
    public void discard() {
        synchronized (lock) {
            if (inputBuffer != null) {
                inputBuffer.getByteBuf().release();
                inputBuffer = null;
            }
            if (inputOverflow != null) {
                Buffer d = inputOverflow.poll();
                while (d != null) {
                    d.getByteBuf().release();
                    d = inputOverflow.poll();
                }
            }
        }
        stream.pause();
        stream.handler(null);
        stream.exceptionHandler(null);
        stream.endHandler(null);
        stream.resume();
    }
}
