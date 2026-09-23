package com.iptv.player.data.repository

import com.iptv.player.util.Outcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class MovieRefreshCategory(val id: String, val name: String)
data class MovieCategoryFailure(val category: MovieRefreshCategory, val failure: Outcome.Failure)

data class MovieCatalogRefreshReport(
    val total: Int,
    val completed: Int,
    val failures: List<MovieCategoryFailure>,
    val indexFailure: Outcome.Failure? = null,
) {
    val successful: Boolean get() = completed == total && failures.isEmpty() && indexFailure == null
}

/** Sequential, bounded-memory sweep. A failed fetch never skips later categories. */
internal object MovieCatalogRefresh {
    suspend fun run(
        categories: List<MovieRefreshCategory>,
        indexFailure: Outcome.Failure? = null,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        fetch: suspend (String) -> Outcome<Int>,
    ): MovieCatalogRefreshReport {
        val failures = mutableListOf<MovieCategoryFailure>()
        var completed = 0
        onProgress(0, categories.size)
        for (category in categories) {
            currentCoroutineContext().ensureActive()
            when (val result = fetch(category.id)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> failures += MovieCategoryFailure(category, result)
            }
            completed++
            onProgress(completed, categories.size)
        }
        return MovieCatalogRefreshReport(categories.size, completed, failures.toList(), indexFailure)
    }
}
