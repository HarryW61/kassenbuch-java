// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.nio.charset.Charset;

/** The legacy files (DBF records, MEMO data) are stored in the DOS codepage 437. */
public final class LegacyCharset {
    public static final Charset CODEPAGE = Charset.forName("IBM437");
}
