/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

/**
 * The registry of all Quartus components which are simulated natively.
 * The components are organized in folders like in the Quartus symbol dialog.
 */
object QuartusParts {
    private val parts = LinkedHashMap<String, QuartusPart>()

    init {
        val add: (QuartusPart) -> Unit = { parts[it.quartusName.lowercase()] = it }
        Primitives.create(add)
        Megafunctions.create(add)
        Macrofunctions.create(add)
    }

    /** returns the part with the given Quartus name (case insensitive) or null */
    @JvmStatic
    fun get(quartusName: String): QuartusPart? = parts[quartusName.lowercase()]

    /** all parts */
    @JvmStatic
    fun getAll(): Collection<QuartusPart> = parts.values

    /** the parts grouped by their folder, e.g. "primitives/logic" */
    @JvmStatic
    fun getCategories(): Map<String, List<QuartusPart>> = parts.values.groupByTo(LinkedHashMap()) { it.category }

    /** true if the given name is a megafunction which is simulated natively */
    @JvmStatic
    fun isMegafunction(name: String): Boolean = get(name)?.category?.startsWith("megafunctions") == true
}
