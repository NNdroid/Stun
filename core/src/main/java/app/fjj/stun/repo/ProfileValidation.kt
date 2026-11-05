package app.fjj.stun.repo

/**
 * Single source of truth for the **legality** of the three myssh 24195a2 xhttp tuning fields.
 *
 * [ProfileFields] owns "how an incoming value is read" (trim / clamp / enum fallback);
 * this file owns "whether the resulting value is acceptable to the SDK". Native UI
 * ([app.fjj.stun.ui.FieldRules]) and every write path ([ProfileManager]) delegate here.
 *
 * Why the hard gate lives in core rather than in [ProfileFields]: profiles also arrive
 * through Gson deserialization that bypasses [ProfileFields] entirely — WebUI/MCP
 * import, WebDAV backup restore, Bluetooth sync, subscription nodes, QR share.
 * [ProfileManager.addProfile] / [updateProfile] / [saveProfiles] is the only choke point
 * all of them share, so [sanitize] there guarantees no illegal value ever reaches Room.
 *
 * Rules mirror xhttptunnel `tunnel/padding.go` `parsePaddingSpec`. The browser editor
 * (assets/web/app.js) necessarily keeps a hand-written mirror (different runtime);
 * `scripts/check-xhttp-validation-parity.mjs` runs both against the same case table
 * in `core/src/test/resources/xhttp-validation-cases.json`.
 *
 * All predicates take the **raw string** form, even when called with an Int: converting
 * via `toString()` and reusing the string predicate keeps one single decision path,
 * so the UI (String) and the persistence layer (Int) can never drift apart.
 */
object ProfileValidation {

    /** SDK rejects windowBytes outside this range; 0 = SDK default 8 MiB. */
    const val XHTTP_WINDOW_MAX_MB = 64

    /** Keep JS-side parity: Kotlin Int is 32-bit, so reject anything above this. */
    const val INT_MAX = Int.MAX_VALUE

    /**
     * xhttp_padding shape check.
     *
     * - blank / auto / default / off / none (case- and whitespace-insensitive)
     * - otherwise comma-separated `MIN-MAX` or `BELOW:MIN-MAX` bands, at most 8
     * - each band: MIN>=1, MIN<=MAX<=65535, explicit threshold>=1, MIN>=BELOW-1,
     *   thresholds strictly increasing
     *
     * This is deliberately only a *static* "can the SDK parse it" check. The SDK also
     * enforces a process-level policy conflict between profiles, which only Go can
     * decide at startup.
     */
    fun isXhttpPadding(value: String): Boolean {
        val spec = value.trim().lowercase()
        if (spec in setOf("", "auto", "default", "off", "none")) return true
        val parts = spec.split(',')
        if (parts.size > 8) return false
        var previousBelow = -1
        for (raw in parts) {
            val part = raw.trim()
            if (part.isEmpty()) return false
            var explicitBelow: Int? = null
            var range = part
            val colon = part.indexOf(':')
            if (colon >= 0) {
                explicitBelow = parsePaddingSize(part.substring(0, colon)) ?: return false
                range = part.substring(colon + 1)
            }
            // SDK uses strings.Cut(range, "-"): only the FIRST '-' counts, and +/- are rejected.
            val dash = range.indexOf('-')
            if (dash < 0) return false
            val minTotal = parsePaddingSize(range.substring(0, dash)) ?: return false
            val maxTotal = parsePaddingSize(range.substring(dash + 1)) ?: return false
            if (minTotal < 1 || minTotal > maxTotal || maxTotal > 65535) return false
            val below = explicitBelow ?: minTotal
            if (below < 1 || minTotal < below - 1 || below <= previousBelow) return false
            previousBelow = below
        }
        return true
    }

    /** SDK parsePaddingSize: trimmed decimal digits only; '+', '-' and blank are rejected. */
    private fun parsePaddingSize(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty() || !t.all { it in '0'..'9' }) return null
        return t.toIntOrNull()
    }

    /** 0 = SDK default 8 MiB; explicit range 1..64. */
    fun isXhttpWindowSizeMB(value: String): Boolean =
        value.isEmpty() || (value.toIntOrNull()?.let { it in 0..XHTTP_WINDOW_MAX_MB } == true)

    /** 0 = SDK default 2000; negative values are rejected by the UI, so reject them here too. */
    fun isXhttpMaxConns(value: String): Boolean =
        value.isEmpty() || (value.toIntOrNull()?.let { it >= 0 } == true)

    /**
     * Human-readable errors for a profile that is about to be stored.
     *
     * Only xhttp profiles are checked: [app.fjj.stun.service.VpnConfigBuilder] emits
     * these three keys exclusively for `TUNNEL_TYPE_XHTTP`, so stale values left on
     * other tunnel types are inert and must not block an otherwise valid save.
     */
    fun errorsOf(profile: Profile): List<String> {
        if (profile.tunnelType != Profile.TUNNEL_TYPE_XHTTP) return emptyList()
        val out = mutableListOf<String>()
        if (!isXhttpPadding(profile.xhttpPadding)) {
            out += "xhttpPadding: use 400-600, BELOW:MIN-MAX bands, off, or leave blank"
        }
        if (!isXhttpWindowSizeMB(profile.xhttpWindowSizeMB.toString())) {
            out += "xhttpWindowSizeMB: must be 0 or 1..$XHTTP_WINDOW_MAX_MB"
        }
        if (!isXhttpMaxConns(profile.xhttpMaxConns.toString())) {
            out += "xhttpMaxConns: must be 0 or a positive integer"
        }
        return out
    }

    /**
     * Last-resort normalization before a write.
     *
     * Rejecting here would abort an entire batch import / backup restore because of
     * one bad legacy node, so instead illegal values fall back to the SDK defaults
     * (empty padding rule, 0 = SDK default for both numeric fields). Interactive
     * editors call [errorsOf] first and report a precise error to the user.
     *
     * Returns the original instance when nothing needs fixing, avoiding needless copies.
     */
    fun sanitize(profile: Profile): Profile {
        if (profile.tunnelType != Profile.TUNNEL_TYPE_XHTTP) return profile
        val padding = if (isXhttpPadding(profile.xhttpPadding)) profile.xhttpPadding else ""
        val window = if (isXhttpWindowSizeMB(profile.xhttpWindowSizeMB.toString())) profile.xhttpWindowSizeMB else 0
        val conns = if (isXhttpMaxConns(profile.xhttpMaxConns.toString())) profile.xhttpMaxConns else 0
        return if (padding == profile.xhttpPadding &&
            window == profile.xhttpWindowSizeMB &&
            conns == profile.xhttpMaxConns
        ) {
            profile
        } else {
            profile.copy(xhttpPadding = padding, xhttpWindowSizeMB = window, xhttpMaxConns = conns)
        }
    }
}
