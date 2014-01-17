package io.netty.handler.sasl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.RealmChoiceCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import java.io.IOException;
import java.util.Map;

public abstract class AbstractSaslClientHandler extends ChannelHandlerAdapter implements CallbackHandler {

    private static final InternalLogger logger =
        InternalLoggerFactory.getInstance(AbstractSaslClientHandler.class);

    private final String authId;
    private final String protocol;
    private final Map<String, ?> props;

    private final LazyChannelPromise authPromise = new LazyChannelPromise();
    private volatile ChannelHandlerContext ctx;

    public AbstractSaslClientHandler(String authId, String protocol, Map<String, ?> props) {
        this.authId = authId;
        this.protocol = protocol;
        this.props = props;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        authenticate().addListener(new GenericFutureListener<Future<? super Channel>>() {
            @Override
            public void operationComplete(Future<? super Channel> future) throws Exception {
                if (!future.isSuccess()) {
                    logger.debug("Failed to complete authentication", future.cause());
                    ctx.close();
                }
            }
        });

        ctx.fireChannelActive();
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;

        if (ctx.channel().isActive()) {
            authenticate();
        }
    }

    private Future<Channel> authenticate() {
        // todo: handle timeouts like sasl
        try {
            authenticate0();
            ctx.pipeline().remove(this);
        } catch (Exception e) {
            // todo: notify failure
        }
        return authPromise;
    }

    private void authenticate0() throws Exception {
        String serverName = ctx.channel().remoteAddress().toString();
        SaslClient client = Sasl.createSaslClient(supportedMechanisms(), authId, protocol, serverName, props, this);

        ByteBuf responseBuf = ctx.alloc().buffer();
        if (client.hasInitialResponse()) {
            responseBuf.writeBytes(client.evaluateChallenge(new byte[0]));
            responseBuf = handleResponse(responseBuf);
        }

        while (!client.isComplete()) {
            byte[] bytes = client.evaluateChallenge(responseBuf);
            responseBuf.clear().writeBytes(bytes);
            responseBuf = handleResponse(responseBuf);
        }
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof NameCallback) {
                handleNameCallback((NameCallback) callback);
            } else if (callback instanceof PasswordCallback) {
                handlePasswordCallback((PasswordCallback) callback);
            } else if (callback instanceof RealmCallback) {
                handleRealmCallback((RealmCallback) callback);
            } else if (callback instanceof RealmChoiceCallback) {
                handleRealmChoiceCallback((RealmChoiceCallback) callback);
            } else {
                handleCustomCallback(callback);
            }
        }
    }

    /**
     * Returns a list of supported SASL mechanisms.
     *
     * The implemented method can either fetch it from the remote service or return a fixed list directly. The list
     * needs to match the
     * <a href="http://docs.oracle.com/javase/7/docs/technotes/guides/security/sasl/sasl-refguide.html">supported</a>
     * SASL mechs. As an example, <pre>new String[] {"CRAM-MD5", "PLAIN"}</pre> is valid.
     *
     * @return the list of supported SASL mechanisms.
     */
    protected abstract String[] supportedMechanisms();

    protected abstract ByteBuf handleResponse(final ByteBuf response);

    /**
     * Handle a {@link NameCallback}.
     *
     * @param callback the callback to handle
     */
    protected void handleNameCallback(final NameCallback callback) {
        logger.info("handleNameCallback called, but not overriden by child handler.");
    }

    /**
     * Handle a {@link PasswordCallback}.
     *
     * @param callback the callback to handle
     */
    protected void handlePasswordCallback(final PasswordCallback callback) {
        logger.info("handlePasswordCallback called, but not overriden by child handler.");
    }

    /**
     * Handle a {@link RealmCallback}.
     *
     * @param callback the callback to handle
     */
    protected void handleRealmCallback(final RealmCallback callback) {
        logger.info("handleRealmCallback called, but not overriden by child handler.");
    }

    /**
     * Handle a {@link RealmChoiceCallback}.
     *
     * @param callback the callback to handle
     */
    protected void handleRealmChoiceCallback(final RealmChoiceCallback callback) {
        logger.info("handleRealmChoiceCallback called, but not overriden by child handler.");
    }

    /**
     * Handle a {@link Callback} that is not explicitly handled by the handler methods.
     *
     * @param callback the callback to handle
     */
    protected void handleCustomCallback(final Callback callback) {
        logger.info("handleCustomCallback called, but not overriden by child handler.");
    }

    private final class LazyChannelPromise extends DefaultPromise<Channel> {

        @Override
        protected EventExecutor executor() {
            if (ctx == null) {
                throw new IllegalStateException();
            }
            return ctx.executor();
        }
    }

}
