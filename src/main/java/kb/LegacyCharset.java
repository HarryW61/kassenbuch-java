package kb;

import java.nio.charset.Charset;

/** The legacy files (DBF records, MEMO data) are stored in the DOS codepage 437. */
public final class LegacyCharset {
    public static final Charset CODEPAGE = Charset.forName("IBM437");
}
