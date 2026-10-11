package app.bolbol.tv.feature.details

import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.flow.Flow

/**
 * What the details screen needs from "لیست من". Defined here (not in core:model) so the module
 * does not depend on the account feature; `:app` adapts `AccountRepository` to it and passes it to
 * [DetailsViewModel.factory]. Without it the "+ لیست من" button is not shown.
 */
interface MyListActions {
    /** Live: true while the title is in the list. */
    fun isInMyList(postId: Long): Flow<Boolean>

    /** Adds ([inList] = true) or removes the title; [title] is stored as a snapshot for offline painting. */
    suspend fun setInMyList(title: TitleSummary, inList: Boolean)
}
