/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteWriter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFlushPromiseNotifier;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.TypeParameterMatcher;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;


/**
 * {@link MessageToByteEncoder} which encodes message in a stream-like fashion from one message to an
 * {@link ByteBuf}. The difference between {@link MessageToBufferedByteEncoder} and {@link MessageToByteEncoder} is
 * that {@link MessageToBufferedByteEncoder}  will try to write multiple writes into one {@link ByteBuf} and only
 * write it to the next {@link ChannelOutboundHandler} in the {@link ChannelPipeline} if either:
 * <ul>
 *   <li>{@link Channel#flush()} is called</li>
 *   <li>{@link Channel#close()} is called</li>
 *   <li>{@link Channel#disconnect()} is called</li>
 * </ul>
 *
 * You should use this {@link MessageToBufferedByteEncoder} if you expect to either write messages in multiple parts
 * or if the used protocol supports PIPELINING.
 *
 * Example implementation which encodes {@link Integer}s to a {@link ByteBuf}.
 *
 * <pre>
 *     public class IntegerEncoder extends {@link MessageToBufferedByteEncoder}&lt;{@link Integer}&gt; {
 *         {@code @Override}
 *         public void encode({@link ChannelHandlerContext} ctx, {@link Integer} msg, {@link ByteBuf} out)
 *                 throws {@link Exception} {
 *             out.writeInt(msg);
 *         }
 *     }
 * </pre>
 */
public abstract class MessageToBufferedByteEncoder<I> extends ChannelOutboundHandlerAdapter {

    private final TypeParameterMatcher matcher;
    private final boolean preferDirect;

    private static final int DEFAULT_BUFFER_SIZE = 1024;
    private final int bufferSize;
    private Encoder writer;

    /**
     * @see {@link #MessageToBufferedByteEncoder(int)} with {@code 1024} as parameter.
     */
    protected MessageToBufferedByteEncoder() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Create a new instance
     *
     * @param bufferSize            The size of the buffer when it is allocated.
     */
    protected MessageToBufferedByteEncoder(int bufferSize) {
        this(true, bufferSize);
    }

    /**
     * Create a new instance
     *
     * @param outboundMessageType   The tpye of messages to match
     * @param bufferSize            The size of the buffer when it is allocated.
     */
    protected MessageToBufferedByteEncoder(Class<? extends I> outboundMessageType, int bufferSize) {
        this(outboundMessageType, true, bufferSize);
    }

    /**
     * Create a new instance
     *
     * @param preferDirect          {@code true} if a direct {@link ByteBuf} should be tried to be used as target for
     *                              the encoded messages. If {@code false} is used it will allocate a heap
     *                              {@link ByteBuf}, which is backed by an byte array.
     * @param bufferSize            The size of the buffer when it is allocated.
     */
    protected MessageToBufferedByteEncoder(boolean preferDirect, int bufferSize) {
        checkSharable();
        checkBufferSize(bufferSize);
        matcher = TypeParameterMatcher.find(this, MessageToBufferedByteEncoder.class, "I");
        this.bufferSize = bufferSize;
        this.preferDirect = preferDirect;
    }

    /**
     * Create a new instance
     *
     * @param outboundMessageType   The tpye of messages to match
     * @param preferDirect          {@code true} if a direct {@link ByteBuf} should be tried to be used as target for
     *                              the encoded messages. If {@code false} is used it will allocate a heap
     *                              {@link ByteBuf}, which is backed by an byte array.
     * @param bufferSize            The size of the buffer when it is allocated.
     */
    protected MessageToBufferedByteEncoder(
            Class<? extends I> outboundMessageType, boolean preferDirect, int bufferSize) {
        checkSharable();
        checkBufferSize(bufferSize);
        matcher = TypeParameterMatcher.get(outboundMessageType);
        this.bufferSize = bufferSize;
        this.preferDirect = preferDirect;
    }

    private static void checkBufferSize(int bufferSize) {
        if (bufferSize < 0) {
            throw new IllegalArgumentException(
                    "bufferSize must be a >= 0: " +
                            bufferSize);
        }
    }

    private void checkSharable() {
        if (getClass().isAnnotationPresent(Sharable.class)) {
            throw new IllegalStateException("@Sharable annotation is not allowed");
        }
    }

    /**
     * Returns {@code true} if the given message should be handled. If {@code false} it will be passed to the next
     * {@link ChannelOutboundHandler} in the {@link ChannelPipeline}.
     */
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return matcher.match(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (acceptOutboundMessage(msg)) {
                @SuppressWarnings("unchecked")
                I cast = (I) msg;

                Encoder writer = this.writer;
                try {
                    encode(ctx, cast, writer);
                    writer.pending(promise);
                } catch (Throwable e) {
                    // TODO: handle
                    throw e;
                } finally {
                    ReferenceCountUtil.release(cast);
                }
            } else {
                writer.flush();
                ctx.write(msg, promise);
            }
        } catch (EncoderException e) {
            throw e;
        } catch (Throwable e) {
            throw new EncoderException(e);
        }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        writer.flush();
        super.close(ctx, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        writer.flush();
        super.disconnect(ctx, promise);
    }

    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, boolean preferDirect, int bufferSize) {
        if (preferDirect) {
            if (bufferSize == 0) {
                return ctx.alloc().ioBuffer();
            }
            return ctx.alloc().ioBuffer(bufferSize);
        } else {
            if (bufferSize == 0) {
                return ctx.alloc().heapBuffer();
            }
            return ctx.alloc().heapBuffer(bufferSize);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        writer.flush();
        super.flush(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        writer = new Encoder(this, ctx);
        super.handlerAdded(ctx);
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        writer.flush();
        handlerRemoved0(ctx);
        super.handlerRemoved(ctx);
    }

    /**
     * Do nothing by default, sub-classes may override this method.
     */
    protected void handlerRemoved0(@SuppressWarnings("unused")ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    /**
     * Encode a message into a {@link ByteBuf}. This method will be called for each written message that can be handled
     * by this encoder.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link MessageToByteEncoder} belongs to
     * @param msg           the message to encode
     * @param out           the {@link Encoder} into which the encoded message will be written
     * @throws Exception    is thrown if an error accour
     */
    protected abstract void encode(ChannelHandlerContext ctx, I msg, Encoder out) throws Exception;

    public static final class Encoder implements ByteWriter {
        private final MessageToBufferedByteEncoder<?> encoder;
        private final ChannelHandlerContext ctx;
        private final List<Object> pending = new ArrayList<Object>();
        private int last = -1;
        private final ChannelFlushPromiseNotifier notifier = new ChannelFlushPromiseNotifier();
        private long delta;

        private Encoder(MessageToBufferedByteEncoder<?> encoder, ChannelHandlerContext ctx) {
            this.encoder = encoder;
            this.ctx = ctx;
        }

        @Override
        public Encoder writeBoolean(boolean value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBoolean(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeByte(int value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeByte(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeShort(int value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeShort(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeMedium(int value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeMedium(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeInt(int value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeInt(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeLong(long value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeLong(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeChar(int value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeChar(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeFloat(float value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeFloat(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeDouble(double value) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeDouble(value);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeBytes(ByteBuf src) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBytes(src);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeBytes(ByteBuf src, int length) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBytes(src, length);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeBytes(ByteBuf src, int srcIndex, int length) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBytes(src, srcIndex, length);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeBytes(byte[] src) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBytes(src);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeBytes(byte[] src, int srcIndex, int length) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBytes(src, srcIndex, length);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        @Override
        public Encoder writeBytes(ByteBuffer src) {
            ByteBuf buffer = wrapped();
            int writerIndex = buffer.writerIndex();
            buffer.writeBytes(src);
            delta += buffer.writerIndex() - writerIndex;
            return this;
        }

        public Encoder writeFileRegion(FileRegion region) {
            pending.add(++last, region);
            delta += region.count();
            return this;
        }

        private ByteBuf wrapped() {
            if (last == -1) {
                ByteBuf buf = encoder.allocateBuffer(ctx, encoder.preferDirect, encoder.bufferSize);
                pending.add(++last, buf);
                return buf;
            }
            Object p = pending.get(last);
            if (!(p instanceof ByteBuf)) {
                ByteBuf buf = encoder.allocateBuffer(ctx, encoder.preferDirect, encoder.bufferSize);
                pending.add(++last, buf);
                p = buf;
            }
            return (ByteBuf) p;
        }

        private void pending(ChannelPromise promise) {
            ctx.channel().unsafe().outboundBuffer().incrementPendingOutboundBytes(delta);
            // add to notifier so the promise will be notified later once we wrote everything
            notifier.add(promise, delta);
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            int size = pending.size();
            for (int i = 0; i < size; i ++) {
                Object msg = pending.get(i);

                final long length = size(msg);

                // Decrement now as we will actually fire stuff through the pipeline
                ctx.channel().unsafe().outboundBuffer().decrementPendingOutboundBytes(length);

                ctx.write(msg).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        notifier.increaseWriteCounter(length);
                        if (future.isSuccess()) {
                            notifier.notifyPromises();
                        } else {
                            notifier.notifyPromises(future.cause());
                        }
                    }
                });
            }
            last = -1;
            pending.clear();
            delta = 0;
        }

        private static long size(Object msg) {
            if (msg instanceof ByteBuf) {
                return ((ByteBuf) msg).readableBytes();
            }
            if (msg instanceof FileRegion) {
                return ((FileRegion) msg).count();
            }
            throw new Error();
        }
    }
}
