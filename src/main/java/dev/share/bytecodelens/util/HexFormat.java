package dev.share.bytecodelens.util;

public final class HexFormat {

    private HexFormat() {
    }

    public static String dump(byte[] bytes, int bytesPerRow) {
        StringBuilder sb = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i % bytesPerRow == 0) {
                if (i > 0) {
                    sb.append("  ").append(ascii).append('\n');
                    ascii.setLength(0);
                }
                sb.append(String.format("%08x  ", i));
            }
            int b = bytes[i] & 0xff;
            sb.append(String.format("%02x ", b));
            ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
        }
        int remaining = bytes.length % bytesPerRow;
        if (remaining > 0) {
            int pad = (bytesPerRow - remaining) * 3;
            for (int i = 0; i < pad; i++) sb.append(' ');
        }
        sb.append("  ").append(ascii);
        return sb.toString();
    }
}
