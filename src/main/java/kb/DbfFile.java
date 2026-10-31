package kb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal dBase III reader/writer.
 *
 * Harbour/xBase writes a slightly unusual field descriptor:
 *   [0..10]  name (NUL padded, max 11 chars)
 *   [11]     type char ('C','N','D','L')
 *   [12..15] reserved / flags
 *   [16]     field length
 *   [17]     decimals
 *   [18..31] reserved
 * Classic dBase III puts the length at [12] and the decimals at [13];
 * both variants are detected by checking which one adds up to the record length.
 */
public final class DbfFile {

    public record Field(String name, char type, int length, int decimals) {}

    private final Path path;
    private final byte[] raw;
    private final byte[] header;      // first 32 bytes
    private final byte[] descriptors; // everything from 32 up to headerLen
    private final List<Field> fields = new ArrayList<>();
    private final List<String[]> records = new ArrayList<>(); // null entry = deleted
    private int headerLen;
    private int recordLen;

    public DbfFile(Path path) throws IOException {
        this.path = path;
        this.raw = Files.readAllBytes(path);
        if (raw.length < 32) throw new IOException("Not a DBF file: " + path);
        header = new byte[32];
        System.arraycopy(raw, 0, header, 0, 32);
        headerLen = u8(raw[8]) | (u8(raw[9]) << 8);
        recordLen = u8(raw[10]) | (u8(raw[11]) << 8);
        int count = u8(raw[4]) | (u8(raw[5]) << 8) | (u8(raw[6]) << 16) | (u8(raw[7]) << 24);
        descriptors = new byte[headerLen - 32];
        System.arraycopy(raw, 32, descriptors, 0, descriptors.length);

        int sum = 0;
        for (int p = 32; p + 32 <= headerLen; p += 32) {
            String name = descriptorName(raw, p);
            if (name.isEmpty()) break;
            char type = (char) raw[p + 11];
            int len = u8(raw[p + 16]);
            int dec = u8(raw[p + 17]);
            fields.add(new Field(name, type, len, dec));
            sum += len;
        }
        // fall back to the classic layout if the Harbour layout does not add up
        if (sum + 1 != recordLen) {
            fields.clear();
            sum = 0;
            for (int p = 32; p + 32 <= headerLen; p += 32) {
                String name = descriptorName(raw, p);
                if (name.isEmpty()) break;
                char type = (char) raw[p + 11];
                int len = u8(raw[p + 12]);
                int dec = u8(raw[p + 13]);
                fields.add(new Field(name, type, len, dec));
                sum += len;
            }
        }

        for (int i = 0; i < count; i++) {
            int off = headerLen + i * recordLen;
            if (off + recordLen > raw.length) break;
            if (raw[off] == '*') { // deleted
                records.add(null);
                continue;
            }
            String[] vals = new String[fields.size()];
            int o = off + 1;
            for (int f = 0; f < fields.size(); f++) {
                Field fd = fields.get(f);
                vals[f] = new String(raw, o, fd.length(), LegacyCharset.CODEPAGE);
                o += fd.length();
            }
            records.add(vals);
        }
    }

    private static String descriptorName(byte[] b, int p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            byte c = b[p + i];
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static int u8(byte b) { return b & 0xff; }

    public List<Field> fields() { return fields; }
    public int recordCount() { return records.size(); }

    /** Live records only (deleted ones skipped), in file order. */
    public List<String[]> live() {
        List<String[]> out = new ArrayList<>();
        for (String[] r : records) if (r != null) out.add(r);
        return out;
    }

    public int fieldIndex(String field) {
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).name().equalsIgnoreCase(field)) return i;
        throw new IllegalArgumentException("No such field: " + field);
    }

    public String get(String[] rec, String field) { return rec[fieldIndex(field)].trim(); }

    public double num(String[] rec, String field) {
        String s = get(rec, field).replace(',', '.');
        return s.isEmpty() ? 0.0 : Double.parseDouble(s);
    }

    /** Add a record; values are keyed by field name. Dates must be yyyyMMdd. */
    public void append(java.util.Map<String, String> values) {
        String[] recVals = new String[fields.size()];
        for (int i = 0; i < fields.size(); i++) recVals[i] = values.getOrDefault(fields.get(i).name(), "");
        records.add(recVals);
    }

    /** Mark a record deleted (dBase III soft delete). */
    public void delete(String[] rec) {
        for (int i = 0; i < records.size(); i++) if (records.get(i) == rec) records.set(i, null);
    }

    private static byte[] padRight(String s, int len) {
        String t = s.length() > len ? s.substring(0, len) : s;
        while (t.length() < len) t += " ";
        return t.getBytes(LegacyCharset.CODEPAGE);
    }

    private static byte[] formatNumber(String v, int dec, int len) {
        double d = v == null || v.isBlank() ? 0.0 : Double.parseDouble(v.replace(',', '.'));
        String s = String.format("%." + dec + "f", d);
        return padLeft(s, len);
    }

    private static byte[] padLeft(String s, int len) {
        String t = s.length() > len ? s.substring(0, len) : s;
        while (t.length() < len) t = " " + t;
        return t.getBytes(LegacyCharset.CODEPAGE);
    }

    /** Write the (possibly modified) file back to disk. */
    /** the date in bytes 17..19 (yy, mm, dd) - zero when the file was never written */
    public int[] modificationDate() {
        return new int[]{u8(raw[17]), u8(raw[18]), u8(raw[19])};
    }

    public void save() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] head = header.clone();
        int live = 0;
        for (String[] r : records) if (r != null) live++;
        head[4] = (byte) (live & 0xff);
        head[5] = (byte) ((live >> 8) & 0xff);
        head[6] = (byte) ((live >> 16) & 0xff);
        head[7] = (byte) ((live >> 24) & 0xff);
        out.write(head);
        out.write(descriptors);
        out.write(raw, 32 + descriptors.length, Math.max(0, headerLen - 32 - descriptors.length));

        List<byte[]> packed = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            String[] r = records.get(i);
            if (r == null) continue;
            byte[] rec = new byte[recordLen];
            rec[0] = ' ';
            int o = 1;
            for (int f = 0; f < fields.size(); f++) {
                Field fd = fields.get(f);
                String v = r[f] == null ? "" : r[f];
                byte[] b = switch (fd.type()) {
                    case 'N' -> formatNumber(v, fd.decimals(), fd.length());
                    case 'D' -> padRight(v.replaceAll("[^0-9]", ""), fd.length());
                    default -> padRight(v, fd.length());
                };
                System.arraycopy(b, 0, rec, o, fd.length());
                o += fd.length();
            }
            packed.add(rec);
        }
        for (byte[] rec : packed) out.write(rec);
        Files.write(path, out.toByteArray());
    }
}
