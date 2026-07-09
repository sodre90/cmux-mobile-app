package com.sodre90.cmuxremote.data

/**
 * The phone-local workspace-ordering surface [SessionsViewModel][com.sodre90.cmuxremote.ui.sessions.SessionsViewModel]
 * consumes -- see [WorkspaceOrderStore], which [AppContainer] delegates to.
 */
interface WorkspaceOrderGateway {
    fun loadOrder(): List<String>
    fun saveOrder(order: List<String>)
}
