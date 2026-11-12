package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Asset
import com.opslegal.tda.core.model.Board
import java.time.LocalDate

/**
 * My things: 8 categories, each with kinds (sub-categories) and the key data that kind keeps. Date fields (renewals, ends)
 * show on the thing's timeline by themselves. A thing's status, its dated items, and the search: rules only, no AI.
 */
object Things {
    data class Field(val name: String, val type: String = "")   // type: "" text, "date", "$"
    data class Cat(val key: String, val icon: String, val name: String, val kinds: List<String>, val fields: List<Field>)

    val CATS = listOf(
        Cat("re", "🏠", "Real estate", listOf("Home", "Rental unit", "Cottage", "Land", "Commercial"),
            listOf(Field("Address"), Field("Bought", "date"), Field("Price", "$"), Field("Mortgage", "$"), Field("Property tax / yr", "$"), Field("Tenant"), Field("Rent / month", "$"), Field("Lease end", "date"))),
        Cat("ve", "🚗", "Vehicles", listOf("Car", "Motorcycle", "Bike", "Boat", "Trailer"),
            listOf(Field("Plate or serial"), Field("Year"), Field("Km"), Field("Insurer"), Field("Insurance renews", "date"), Field("Registration due", "date"))),
        Cat("co", "⌚", "Collectibles", listOf("Watch", "Art", "Jewelry", "Coins & stamps", "Wine"),
            listOf(Field("Maker"), Field("Serial"), Field("Appraised value", "$"), Field("Appraised on", "date"), Field("Kept at"))),
        Cat("be", "🛋", "Belongings", listOf("Electronics", "Appliances", "Furniture", "Tools", "Sports gear"),
            listOf(Field("Model"), Field("Serial"), Field("Bought", "date"), Field("Warranty end", "date"))),
        Cat("fi", "💰", "Finance", listOf("Bank account", "Investments", "Retirement", "Loan", "Insurance policy"),
            listOf(Field("Institution"), Field("Last 4 digits"), Field("Renewal", "date"), Field("Beneficiary"))),
        Cat("bu", "🏢", "Business", listOf("My company", "Shares", "Partnership"),
            listOf(Field("Ownership %"), Field("Fiscal year end", "date"), Field("Partners"))),
        Cat("ag", "📜", "Agreements", listOf("Contract", "Lease (I rent)", "Subscription", "Membership", "Warranty plan"),
            listOf(Field("Other party"), Field("Starts", "date"), Field("Ends", "date"), Field("Notice (days)"), Field("Cost / month", "$"))),
        Cat("ot", "📦", "Other", listOf("Pets", "Domain names", "Garden", "Other"), listOf(Field("Details"))),
    )
    private val OLD = mapOf("car" to ("ve" to "Car"), "bike" to ("ve" to "Bike"), "home" to ("re" to "Home"), "rental" to ("re" to "Rental unit"),
        "watch" to ("co" to "Watch"), "art" to ("co" to "Art"), "other" to ("ot" to "Other"))
    val KIND_ICON = mapOf("Home" to "🏠", "Rental unit" to "🏢", "Cottage" to "🏡", "Land" to "🌳", "Commercial" to "🏬", "Car" to "🚗", "Motorcycle" to "🏍",
        "Bike" to "🚲", "Boat" to "⛵", "Trailer" to "🚚", "Watch" to "⌚", "Art" to "🖼", "Jewelry" to "💍", "Coins & stamps" to "🪙", "Wine" to "🍷",
        "Electronics" to "💻", "Appliances" to "🧺", "Furniture" to "🛋", "Tools" to "🛠", "Sports gear" to "🎿", "Bank account" to "🏦", "Investments" to "📈",
        "Retirement" to "🌅", "Loan" to "💳", "Insurance policy" to "🛡", "My company" to "🏢", "Shares" to "📊", "Partnership" to "🤝", "Contract" to "📜",
        "Lease (I rent)" to "🔑", "Subscription" to "🔁", "Membership" to "🎫", "Warranty plan" to "🧾", "Pets" to "🐾", "Domain names" to "🌐", "Garden" to "🌱")

    /** Its category (older things kept a type: mapped once). */
    fun cat(a: Asset): Cat = CATS.firstOrNull { it.key == a.cat } ?: OLD[a.type]?.let { (k, _) -> CATS.first { it.key == k } } ?: CATS.last()
    fun kind(a: Asset): String = a.sub.ifBlank { OLD[a.type]?.second ?: cat(a).kinds.first() }
    fun icon(a: Asset): String = KIND_ICON[kind(a)] ?: cat(a).icon

    /** One dated item of a thing: upkeep (🔧), a date from its data (📅), a task (📋), a quick thing (⚡), an expense (💲). */
    data class Item(val date: String, val icon: String, val title: String, val kind: String, val ref: String = "")

    /** Every dated item until [until]: upkeep repeated by its interval, date fields, its tasks and quick things, its expenses. */
    fun items(board: Board, a: Asset, today: LocalDate, until: LocalDate = today.plusDays(400)): List<Item> {
        val out = mutableListOf<Item>()
        for (u in a.upkeep) {
            var d = runCatching { LocalDate.parse(u.next) }.getOrNull()
            var k = 0
            while (d != null && !d.isAfter(until) && k++ < 24) { out += Item(d.toString(), "🔧", u.what, "upkeep", u.id); d = d.plusMonths(u.every.coerceAtLeast(1).toLong()) }
        }
        cat(a).fields.filter { it.type == "date" }.forEach { f ->
            a.fields[f.name]?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess && it >= today.minusDays(31).toString() }?.let { out += Item(it, "📅", f.name, "date", f.name) }
        }
        board.tasks.filter { it.asset == a.id }.forEach { t -> t.steps.filter { it.date != null && !it.done }.forEach { out += Item(it.date!!, "📋", it.title, "task", it.id) } }
        board.quick.filter { it.asset == a.id && !it.done && (it.due.isNotBlank() || it.date.isNotBlank()) }
            .forEach { q -> out += Item(q.due.ifBlank { q.date }, "⚡", q.title.removePrefix("🔧 "), "quick", q.id) }
        a.expenses.forEach { e -> out += Item(e.date, "💲", "${e.vendor} ${Paperwork.money(e.amount)}", "expense", e.id) }
        return out.sortedBy { it.date }
    }

    /** "bad" = something late, "warn" = due within 30 days, "ok" = nothing due. */
    fun status(board: Board, a: Asset, today: LocalDate): String {
        val dates = items(board, a, today).filter { it.kind != "expense" }.map { it.date }
        return when {
            dates.any { it < today.toString() } -> "bad"
            dates.any { it <= today.plusDays(30).toString() } -> "warn"
            else -> "ok"
        }
    }

    /** Things matching every word: name, kind, category, description, key data, document names. */
    fun search(board: Board, query: String): List<Asset> {
        val words = Paperwork.fold(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return board.assets.filter { a ->
            val hay = Paperwork.fold((listOf(a.name, kind(a), cat(a).name, a.desc, a.details) + a.fields.flatMap { listOf(it.key, it.value) } + a.docs.map { it.name }).joinToString(" "))
            words.all { hay.contains(it) }
        }
    }

    /** Kinds matching the words (to add one). */
    fun kinds(query: String): List<Pair<Cat, String>> {
        val words = Paperwork.fold(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return CATS.flatMap { c -> c.kinds.map { c to it } }.filter { (c, k) -> Paperwork.fold("${c.name} $k").let { h -> words.all { h.contains(it) } } }
    }
}
