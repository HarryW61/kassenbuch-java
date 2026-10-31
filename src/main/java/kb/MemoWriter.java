// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes a Harbour MEMO file back out (round-trips the STAMM.MEM layout). */
public final class MemoWriter {

    public static void write(Path path, Map<String, String> values) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String name = e.getKey();
            if (name.length() > 10) name = name.substring(0, 10);
            byte[] data = e.getValue() == null ? new byte[0] : e.getValue().getBytes(LegacyCharset.CODEPAGE);
            if (data.length > 255) data = java.util.Arrays.copyOf(data, 255);
            byte[] desc = new byte[32];
            byte[] n = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(n, 0, desc, 0, n.length);
            desc[11] = (byte) 0xc3;          // string
            desc[16] = (byte) data.length;
            desc[17] = 0;
            out.write(desc);
            out.write(data);
        }
        Files.write(path, out.toByteArray());
    }
}
