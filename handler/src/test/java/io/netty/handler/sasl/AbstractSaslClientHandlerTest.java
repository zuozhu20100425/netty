package io.netty.handler.sasl;


import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import java.util.HashMap;

public class AbstractSaslClientHandlerTest {

    @Test
    public void foo() {
        SimpleSaslClientHandler handler = new SimpleSaslClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
    }


    static class SimpleSaslClientHandler extends AbstractSaslClientHandler {

        private static final String AUTH_ID = "authId";
        private static final String PROTOCOL = "simple";

        SimpleSaslClientHandler() {
            super(AUTH_ID, PROTOCOL, new HashMap<String, Object>());
        }

        @Override
        protected String[] supportedMechanisms() {
            return new String[] {"CRAM-MD5", "PLAIN"};
        }

        @Override
        protected ByteBuf handleResponse(ByteBuf response) {
            System.out.println("Handle response called");
            return null;
        }

        @Override
        protected void handleNameCallback(NameCallback callback) {
            callback.setName("simpleName");
        }

        @Override
        protected void handlePasswordCallback(PasswordCallback callback) {
            callback.setPassword("simplePassword".toCharArray());
        }
    }
}
