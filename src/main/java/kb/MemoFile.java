// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harbour/xBase MEMO file ("SAVE TO stamm ALL LIKE ST_*").
 *
 * Each entry is a 32-byte descriptor followed by its data:
 *   [0..10]  name (NUL padded, max 10 chars in Harbour)
 *   [11]     type byte (0xC3 = string, 0xCE = numeric)
 *   [12..15] reserved
 *   [16]     data length, [17] decimals
 *   [18..31] reserved
 * Numeric entries store an 8-byte little-endian double as their data.
 * The byte at [16] is not always trustworthy, so the entry size is taken
 * from the distance to the next descriptor.
 */
public final class MemoFile {

    public static Map<String, String> read(Path path) throws IOException {
        byte[] b = Files.readAllBytes(path);
        List<Integer> starts = new ArrayList<>();
        for (int p = 0; p + 32 <= b.length; p++) {
            if (isDescriptor(b, p)) starts.add(p);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            int p = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : b.length;
            int dataStart = p + 32;
            int dataLen = Math.max(0, end - dataStart);
            String name = name(b, p);
            char type = (char) (b[p + 11] & 0xff);
            String data;
            if (type == 0xCE && dataLen >= 8) {
                long bits = 0;
                for (int k = 7; k >= 0; k--) bits = (bits << 8) | (b[dataStart + k] & 0xffL);
                data = String.valueOf(Double.longBitsToDouble(bits));
            } else {
                data = new String(b, dataStart, dataLen, kb.LegacyCharset.CODEPAGE);
                int nul = data.indexOf('\0');
                if (nul >= 0) data = data.substring(0, nul);
            }
            out.put(name, data.trim());
        }
        return out;
    }

    private static boolean isDescriptor(byte[] b, int p) {
        int i = p;
        while (i < p + 11 && (b[i] & 0xff) != 0) i++;
        int nameLen = i - p;
        if (nameLen < 1 || nameLen > 10) return false;
        for (int k = p; k < i; k++) {
            char c = (char) (b[k] & 0xff);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        int type = b[p + 11] & 0xff;
        if (type < 0xc0 || type > 0xcf) return false;
        return b[p + 12] == 0 && b[p + 13] == 0 && b[p + 14] == 0 && b[p + 15] == 0;
    }

    private static String name(byte[] b, int p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            byte c = b[p + i];
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }
}
