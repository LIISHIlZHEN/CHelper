/**
 * It is part of CHelper. CHelper is a command helper for Minecraft Bedrock Edition.
 * Copyright (C) 2026  Akanyi
 *
 * 云端命令库「收藏」独立存储。
 * 与本地草稿库（LocalCommandLabDataStore）分开：收藏只做书签，
 * 点击后跳转公有库详情，id 必须是云端 public id。
 */

package yancey.chelper.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import yancey.chelper.network.library.data.LibraryFunction
import java.io.InputStream
import java.io.OutputStream

private val Context.favoriteLibraryDataStore: DataStore<FavoriteLibraryData> by dataStore(
    fileName = "favorite_library.json",
    serializer = FavoriteLibrarySerializer
)

@Serializable
data class FavoriteLibraryData(
    val functions: List<LibraryFunction> = emptyList(),
    val serverMigrationComplete: Boolean = false
)

object FavoriteLibrarySerializer : Serializer<FavoriteLibraryData> {
    override val defaultValue: FavoriteLibraryData = FavoriteLibraryData()

    override suspend fun readFrom(input: InputStream): FavoriteLibraryData =
        try {
            withContext(Dispatchers.IO) {
                Json.decodeFromString<FavoriteLibraryData>(
                    input.readBytes().decodeToString()
                )
            }
        } catch (serialization: SerializationException) {
            throw CorruptionException("Unable to read FavoriteLibraryData", serialization)
        }

    override suspend fun writeTo(t: FavoriteLibraryData, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(
                Json.encodeToString(t).encodeToByteArray()
            )
        }
    }
}

class FavoriteLibraryDataStore(private val context: Context) {

    fun favorites(): Flow<List<LibraryFunction>> =
        context.favoriteLibraryDataStore.data.map { it.functions }

    suspend fun pendingServerMigration(): List<LibraryFunction> {
        val data = context.favoriteLibraryDataStore.data.first()
        return if (data.serverMigrationComplete) emptyList() else data.functions
    }

    suspend fun markServerMigrationComplete() {
        context.favoriteLibraryDataStore.updateData { current ->
            current.copy(functions = emptyList(), serverMigrationComplete = true)
        }
    }

    /** 按云端 public id 收藏；已存在则 false，否则 true */
    suspend fun addFavorite(function: LibraryFunction): Boolean {
        var added = false
        context.favoriteLibraryDataStore.updateData { current ->
            val cloudId = function.id
            if (cloudId != null && current.functions.any { it.id == cloudId }) {
                current
            } else {
                added = true
                current.copy(functions = listOf(function) + current.functions)
            }
        }
        return added
    }

    suspend fun removeFavoriteByCloudId(cloudId: Int) {
        context.favoriteLibraryDataStore.updateData { current ->
            current.copy(functions = current.functions.filter { it.id != cloudId })
        }
    }

    suspend fun removeFavoriteAt(index: Int) {
        context.favoriteLibraryDataStore.updateData { current ->
            val list = current.functions.toMutableList()
            if (index in list.indices) list.removeAt(index)
            current.copy(functions = list)
        }
    }

}
