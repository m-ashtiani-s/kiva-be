package com.kiva.common.usecase

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class UseCaseTest {

    // --- A tiny in-memory "shop" so the use cases have something to work on ---

    private class ProductStore {
        private val names = mutableMapOf<Long, String>()
        private var nextId = 1L

        fun add(name: String): Long = nextId++.also { names[it] = name }
        fun find(id: Long): String? = names[id]
        fun count() = names.size
    }

    // --- Command: changes something and returns the new id ---

    private data class AddProductCmd(val name: String)

    private sealed class AddProductFailure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "name") {
        data object NameBlank : AddProductFailure("PRODUCT_NAME_BLANK")
    }

    private class AddProductUc(private val store: ProductStore) : CommandUseCase<AddProductCmd, Long, AddProductFailure> {
        override suspend fun execute(command: AddProductCmd): Result<Long, AddProductFailure> {
            if (command.name.isBlank()) return Result.failure(AddProductFailure.NameBlank)
            return Result.success(store.add(command.name.trim()))
        }
    }

    // --- Query: only reads ---

    private data class GetProductQry(val id: Long)

    private data object ProductNotFound : ResultFailure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

    private class GetProductUc(private val store: ProductStore) : QueryUseCase<GetProductQry, String, ProductNotFound> {
        override suspend fun execute(query: GetProductQry): Result<String, ProductNotFound> {
            val name = store.find(query.id) ?: return Result.failure(ProductNotFound)
            return Result.success(name)
        }
    }

    private val store = ProductStore()
    private val addProduct = AddProductUc(store)
    private val getProduct = GetProductUc(store)

    @Test
    fun `a command changes state and can return the created id`() = runTest {
        val id = addProduct.execute(AddProductCmd("  Mug ")).unwrapOr { error("unexpected $it") }

        assertEquals(1L, id)
        assertEquals("Mug", store.find(id))
    }

    @Test
    fun `a failing command changes nothing`() = runTest {
        assertEquals(Result.failure(AddProductFailure.NameBlank), addProduct.execute(AddProductCmd(" ")))
        assertEquals(0, store.count())
    }

    @Test
    fun `a query reads without changing anything`() = runTest {
        val id = store.add("Mug")

        assertEquals(Result.success("Mug"), getProduct.execute(GetProductQry(id)))
        assertEquals(Result.success("Mug"), getProduct.execute(GetProductQry(id)))
        assertEquals(1, store.count())
    }

    @Test
    fun `a query reports a missing item as NOT_FOUND`() = runTest {
        val failure = getProduct.execute(GetProductQry(42)).failureOrNull()
        assertEquals(FailureKind.NOT_FOUND, failure?.kind)
    }
}
