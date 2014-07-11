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

package io.netty.handler.codec.http;

import io.netty.buffer.ByteWriter;
import io.netty.handler.codec.AsciiString;
import io.netty.handler.codec.TextHeaderProcessor;

final class HttpHeadersEncoder implements TextHeaderProcessor {

    private final ByteWriter writer;

    HttpHeadersEncoder(ByteWriter writer) {
        this.writer = writer;
    }

    @Override
    public boolean process(CharSequence name, CharSequence value) throws Exception {
        final ByteWriter writer = this.writer;
        final int nameLen = name.length();
        final int valueLen = value.length();
        writeAscii(writer, name, nameLen);
        writer.writeByte(':');
        writer.writeByte(' ');
        writeAscii(writer, value, valueLen);
        writer.writeByte('\r');
        writer.writeByte('\n');
        return true;
    }

    private static void writeAscii(ByteWriter writer, CharSequence value, int valueLen) {
        if (value instanceof AsciiString) {
            writeAsciiString(writer, (AsciiString) value, valueLen);
        } else {
            writeCharSequence(writer, value, valueLen);
        }
    }

    private static void writeAsciiString(ByteWriter writer, AsciiString value, int valueLen) {
        value.copy(0, writer, valueLen);
    }

    private static void writeCharSequence(ByteWriter writer, CharSequence value, int valueLen) {
        for (int i = 0; i < valueLen; i ++) {
            writer.writeByte(c2b(value.charAt(i)));
        }
    }

    private static int c2b(char ch) {
        return ch < 256? (byte) ch : '?';
    }
}
