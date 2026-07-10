package com.sodre90.cmuxremote.data

/**
 * The phone-local sessions-list display-preference surface
 * [SessionsViewModel][com.sodre90.cmuxremote.ui.sessions.SessionsViewModel]
 * consumes -- see [WorkspaceOrderStore], which [AppContainer] delegates to.
 */
interface WorkspaceOrderGateway {
    fun loadOrder(): List<String>
    fun saveOrder(order: List<String>)

    /** The persisted "Waiting first" sort toggle -- off (false) by default. */
    fun loadSortByAttention(): Boolean
    fun saveSortByAttention(sortByAttention: Boolean)
}
