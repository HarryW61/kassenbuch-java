// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free PDF writer.
 *
 * Renders the legacy 113-column report pages on A4 landscape (841.89 x 595.28 pt)
 * using the built-in Courier font (monospaced, 600/1000 advance), so the ASCII
 * table stays perfectly aligned - it looks like the dot-matrix printout of 1996.
 *
 * No external library, PDF 1.4, one page per legacy report page.
 */
public final class PdfReport {

    private static final double PAGE_W = 841.89;
    private static final double PAGE_H = 595.28;
    private static final double LEFT = 32;
    private static final double TOP = 562;
    private static final double FONT = 9.0;      // 113 chars * 9 * 0.6 = 610 pt  -> fits
    private static final double LEADING = 10.8;

    public static byte[] render(List<List<String>> pages, String title) throws java.io.IOException {
        int n = Math.max(1, pages.size());
        List<byte[]> objects = new ArrayList<>();   // objects[0] is object number 1

        // 1: catalog
        objects.add(("<< /Type /Catalog /Pages 2 0 R >>").getBytes(StandardCharsets.ISO_8859_1));

        // 2: page tree
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) kids.append(' ');
            kids.append(pageObjectId(i)).append(" 0 R");
        }
        objects.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>").getBytes(StandardCharsets.ISO_8859_1));

        // 3: font
        objects.add(("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
                .getBytes(StandardCharsets.ISO_8859_1));

        // 4..: page + content stream per legacy page
        for (int i = 0; i < n; i++) {
            String page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_W + " " + PAGE_H + "] "
                    + "/Resources << /Font << /F1 3 0 R >> >> /Contents " + contentObjectId(i) + " 0 R >>";
            objects.add(page.getBytes(StandardCharsets.ISO_8859_1));
            objects.add(contentStream(pages.get(i)));
        }

        // info dictionary
        int infoId = objects.size() + 1;
        objects.add(("<< /Title (" + escape(title) + ") /Producer (kassenbuch-java LegacyReport) >>")
                .getBytes(StandardCharsets.ISO_8859_1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int[] offsets = new int[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            out.write(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            out.write(objects.get(i));
            out.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }

        int xrefOffset = out.size();
        out.write(("xref\n0 " + (objects.size() + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write("0000000000 00000 f\r\n".getBytes(StandardCharsets.US_ASCII));
        for (int i = 1; i <= objects.size(); i++) {
            // 20 bytes per entry, as required by the PDF specification
            out.write(String.format("%010d 00000 n\r\n", offsets[i]).getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R /Info " + infoId + " 0 R >>\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(("startxref\n" + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] contentStream(List<String> lines) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("BT\n");
        sb.append("/F1 ").append(FONT).append(" Tf\n");
        sb.append(LEADING).append(" TL\n");
        sb.append(LEFT).append(' ').append(TOP).append(" Td\n");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append("T*\n");
            sb.append('(').append(escape(lines.get(i))).append(") Tj\n");
        }
        sb.append("ET\n");
        byte[] data = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("<< /Length " + data.length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.write("endstream\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static int pageObjectId(int index) { return 4 + 2 * index; }
    private static int contentObjectId(int index) { return 5 + 2 * index; }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '(' || c == ')') sb.append('\\').append(c);
            else if (c < 32 || c > 126) sb.append(String.format("\\%03o", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
