package com.pokemon.catchcombo.data

import java.util.UUID

interface ComboRepository {
    fun initialize()
    fun shutdown()
    fun getComboData(playerUuid: UUID): ComboData?
    fun saveComboData(data: ComboData)
    fun deleteComboData(playerUuid: UUID)
    fun getAllComboData(): List<ComboData>
}
