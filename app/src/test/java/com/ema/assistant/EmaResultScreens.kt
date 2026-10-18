package com.ema.assistant

/**
 * Recorded screen shapes used by the result tests. They mirror what the
 * accessibility layer reports for a typical video-search screen: a search box
 * echoing the query, toolbar icons, filter chips, an ad, and result rows whose
 * click handler is on the row container while the text sits on children.
 * Coordinates are a 1080x2400 portrait screen.
 */
object EmaResultScreens {
    class Builder(val pkg: String) {
        val nodes = mutableListOf<EmaNodeSnapshot>()
        fun node(
            text: String = "", desc: String = "", id: String = "", cls: String = "android.view.View",
            click: Boolean = false, parent: Int = -1, editable: Boolean = false,
            l: Int, t: Int, r: Int, b: Int
        ): Int {
            val i = nodes.size
            nodes += EmaNodeSnapshot(i, text, desc, id, cls, visible = true, enabled = true, clickableSelf = click,
                clickableAncestorIndex = parent, editable = editable, left = l, top = t, right = r, bottom = b)
            return i
        }
        fun row(title: String, meta: String, duration: String?, top: Int, ad: Boolean = false): Int {
            val row = node(cls = "android.view.ViewGroup", click = true, l = 0, t = top, r = 1080, b = top + 560)
            if (duration != null) node(text = duration, parent = row, l = 900, t = top + 360, r = 1060, b = top + 410)
            node(text = title, parent = row, cls = "android.widget.TextView", l = 40, t = top + 430, r = 1000, b = top + 490)
            node(text = if (ad) "Sponsored · $meta" else meta, parent = row, cls = "android.widget.TextView", l = 40, t = top + 495, r = 1000, b = top + 540)
            return row
        }
        fun chrome(query: String) {
            node(text = query, id = "$pkg:id/search_edit_text", cls = "android.widget.EditText", editable = true, click = true, l = 120, t = 60, r = 860, b = 160)
            node(desc = "Navigate up", click = true, l = 0, t = 60, r = 110, b = 160)
            node(desc = "Search with your voice", click = true, l = 870, t = 60, r = 970, b = 160)
            node(desc = "More options", click = true, l = 980, t = 60, r = 1080, b = 160)
            node(text = "All", click = true, l = 20, t = 180, r = 160, b = 250)
            node(text = "Shorts", click = true, l = 180, t = 180, r = 360, b = 250)
            node(text = "Unwatched", click = true, l = 380, t = 180, r = 600, b = 250)
            node(desc = "Home", click = true, l = 0, t = 2280, r = 216, b = 2400)
            node(desc = "Subscriptions", click = true, l = 432, t = 2280, r = 648, b = 2400)
        }
        fun build() = EmaScreenSnapshot(pkg, nodes.toList())
    }

    const val YT = "com.google.android.youtube"

    /** Results for "football": an ad first, then three videos with visible lengths. */
    fun footballWithDurations(): EmaScreenSnapshot = Builder(YT).apply {
        chrome("football")
        row("Best boots of the year", "BootStore · 2.1K views", null, 270, ad = true)
        row("Football highlights: derby day", "Sports Hub · 1.2M views · 2 days ago", "12:34", 840)
        row("Every goal of the season", "League TV · 3.4M views · 1 month ago", "1:02:10", 1410)
    }.build()

    fun footballPage2(): EmaScreenSnapshot = Builder(YT).apply {
        chrome("football")
        row("Every goal of the season", "League TV · 3.4M views · 1 month ago", "1:02:10", 270)
        row("Training drills for beginners", "Coach K · 80K views · 3 weeks ago", "8:05", 840)
        row("Top 10 saves", "Keepers · 500K views · 1 year ago", "10:00", 1410)
    }.build()

    /** Same list but the app shows no lengths (e.g. a different layout). */
    fun footballWithoutDurations(): EmaScreenSnapshot = Builder(YT).apply {
        chrome("football")
        row("Football highlights: derby day", "Sports Hub", null, 270)
        row("Every goal of the season", "League TV", null, 840)
        row("Training drills for beginners", "Coach K", null, 1410)
    }.build()

    /** A private app: results are plain list rows without media metadata. */
    fun portalInvoices(): EmaScreenSnapshot = Builder("com.acme.portal").apply {
        node(text = "invoices", cls = "android.widget.EditText", editable = true, click = true, l = 40, t = 60, r = 1040, b = 160)
        for ((i, name) in listOf("Invoice INV-1001 Lagos office", "Invoice INV-1002 Abuja office", "Invoice INV-1003 Port Harcourt").withIndex()) {
            val top = 220 + i * 200
            val row = node(click = true, cls = "android.widget.LinearLayout", l = 0, t = top, r = 1080, b = top + 180)
            node(text = name, parent = row, l = 40, t = top + 20, r = 1000, b = top + 90)
            node(text = "Due in ${i + 3} days", parent = row, l = 40, t = top + 100, r = 600, b = top + 160)
        }
    }.build()

    /** A video player page: no result list of the original search. */
    fun playerPage(): EmaScreenSnapshot = Builder(YT).apply {
        node(desc = "Pause video", click = true, l = 0, t = 0, r = 1080, b = 600)
        node(text = "Football highlights: derby day", l = 40, t = 620, r = 1000, b = 700)
        node(desc = "Like", click = true, l = 40, t = 720, r = 200, b = 800)
        node(desc = "Share", click = true, l = 220, t = 720, r = 380, b = 800)
    }.build()
}
