/*
 * Copyright 2011-2023 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.netty.util;

import static io.gatling.netty.util.Utf8ByteBufCharsetDecoder.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

public final class ByteBufUtils {

  public static final ByteBuf[] EMPTY_BYTEBUF_ARRAY = new ByteBuf[0];

  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final char[] EMPTY_CHARS = new char[0];
  private static final ThreadLocal<CharBuffer> CHAR_BUFFERS =
      ThreadLocal.withInitial(() -> CharBuffer.allocate(1024));

  private ByteBufUtils() {}

  public static byte[] byteBuf2Bytes(ByteBuf buf) {
    int readable = buf.readableBytes();
    if (readable == 0) {
      return EMPTY_BYTES;
    }
    byte[] bytes = new byte[readable];
    if (buf.hasArray()) {
      System.arraycopy(buf.array(), buf.arrayOffset(), bytes, 0, readable);
    } else {
      buf.getBytes(buf.readerIndex(), bytes);
    }
    return bytes;
  }

  public static byte[] byteBufs2Bytes(ByteBuf... bufs) {
    int size = 0;
    for (ByteBuf buf : bufs) {
      size += buf.readableBytes();
    }

    if (size == 0) {
      return EMPTY_BYTES;
    }

    byte[] bytes = new byte[size];
    int destPos = 0;
    for (ByteBuf buf : bufs) {
      int readable = buf.readableBytes();
      if (buf.hasArray()) {
        System.arraycopy(buf.array(), buf.arrayOffset(), bytes, destPos, readable);
      } else {
        buf.getBytes(buf.readerIndex(), bytes, destPos, readable);
      }
      destPos += readable;
    }

    return bytes;
  }

  public static String byteBuf2String(Charset charset, ByteBuf buf) {
    return isUtf8OrUsAscii(charset) ? decodeUtf8(buf) : decodeString(charset, buf);
  }

  public static String byteBuf2String(Charset charset, ByteBuf... bufs) {
    return isUtf8OrUsAscii(charset) ? decodeUtf8(bufs) : byteBuf2String0(charset, bufs);
  }

  public static char[] byteBuf2Chars(Charset charset, ByteBuf buf) {
    return isUtf8OrUsAscii(charset) ? decodeUtf8Chars(buf) : decodeChars(charset, buf);
  }

  public static char[] byteBuf2Chars(Charset charset, ByteBuf... bufs) {
    return isUtf8OrUsAscii(charset) ? decodeUtf8Chars(bufs) : byteBuf2Chars0(charset, bufs);
  }

  private static boolean isUtf8OrUsAscii(Charset charset) {
    return charset.equals(UTF_8) || charset.equals(US_ASCII);
  }

  static String decodeString(Charset charset, ByteBuf src) {
    if (!src.isReadable()) {
      return "";
    }
    return decode(src, charset).toString();
  }

  static char[] decodeChars(Charset charset, ByteBuf src) {
    if (!src.isReadable()) {
      return EMPTY_CHARS;
    }
    return toCharArray(decode(src, charset));
  }

  static String byteBuf2String0(Charset charset, ByteBuf... bufs) {
    if (bufs.length == 1) {
      return decodeString(charset, bufs[0]);
    }
    ByteBuf composite = composite(bufs);
    try {
      return decodeString(charset, composite);
    } finally {
      composite.release();
    }
  }

  static char[] byteBuf2Chars0(Charset charset, ByteBuf... bufs) {
    if (bufs.length == 1) {
      return decodeChars(charset, bufs[0]);
    }
    ByteBuf composite = composite(bufs);
    try {
      return decodeChars(charset, composite);
    } finally {
      composite.release();
    }
  }

  private static ByteBuf composite(ByteBuf[] bufs) {
    for (ByteBuf buf : bufs) {
      buf.retain();
    }
    return Unpooled.wrappedBuffer(bufs);
  }

  private static void decode(CharsetDecoder decoder, ByteBuffer src, CharBuffer dst) {
    try {
      CoderResult cr = decoder.decode(src, dst, true);
      if (!cr.isUnderflow()) {
        cr.throwException();
      }
      cr = decoder.flush(dst);
      if (!cr.isUnderflow()) {
        cr.throwException();
      }
    } catch (CharacterCodingException x) {
      throw new IllegalStateException(x);
    }
  }

  static char[] toCharArray(CharBuffer charBuffer) {
    char[] chars = new char[charBuffer.remaining()];
    charBuffer.get(chars);
    return chars;
  }

  private static CharBuffer pooledCharBuffer(int len, CharsetDecoder decoder) {
    final int maxLength = (int) ((double) len * decoder.maxCharsPerByte());
    CharBuffer dst = CHAR_BUFFERS.get();
    if (dst.length() < maxLength) {
      dst = CharBuffer.allocate(maxLength);
      CHAR_BUFFERS.set(dst);
    } else {
      dst.clear();
    }
    return dst;
  }

  private static CharBuffer decode(ByteBuf src, Charset charset) {
    int readerIndex = src.readerIndex();
    int len = src.readableBytes();
    final CharsetDecoder decoder = CharsetUtil.decoder(charset);
    CharBuffer dst = pooledCharBuffer(len, decoder);
    if (src.nioBufferCount() == 1) {
      // Use internalNioBuffer(...) to reduce object creation.
      // BEWARE: NOT THREAD-SAFE
      decode(decoder, src.internalNioBuffer(readerIndex, len), dst);
    } else {
      // We use a heap buffer as CharsetDecoder is most likely able to use a fast-path if src and
      // dst buffers
      // are both backed by a byte array.
      ByteBuf buffer = src.alloc().heapBuffer(len);
      try {
        buffer.writeBytes(src, readerIndex, len);
        // Use internalNioBuffer(...) to reduce object creation.
        decode(decoder, buffer.internalNioBuffer(buffer.readerIndex(), len), dst);
      } finally {
        // Release the temporary buffer again.
        buffer.release();
      }
    }
    dst.flip();
    return dst;
  }
}
