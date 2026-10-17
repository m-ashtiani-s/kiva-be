package com.kiva.application.usecase.catalog

import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.required
import com.kiva.common.railway.zip
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor

/**
 * The contract's ColorUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and
 * `PUT /admin/colors*` (CreateColorUc, UpdateColorUc).
 */
data class ColorFields(
    val key: String?,
    val name: String?,
    val hex: String?,
    val isLight: Boolean?,
    val sortOrder: Int?,
) {
    /** [sortOrder] stays null when not sent: on create it goes after the last color, on update it keeps its place. */
    data class Checked(val key: ColorKey, val name: ColorName, val hex: HexColor, val isLight: Boolean, val sortOrder: Int?)

    /** Every problem of the form at once, each under its field. */
    fun check(): Result<Checked, InputProblems> = Result.zip(
        key.required().forField("key").bind { ColorKey.makeNew(it).forField("key") },
        name.required().forField("name").bind { ColorName.makeNew(it) },
        hex.required().forField("hex").bind { HexColor.makeNew(it) },
        ::InputProblems,
    ) { key, name, hex -> Checked(key, name, hex, isLight ?: false, sortOrder) }
}
