package com.nuvio.tv.core.auth

import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * A session status flow that tells a test when its collector has handled the first value.
 *
 * `AuthManager` collects `Auth.sessionStatus` on `Dispatchers.IO` from its constructor, and its
 * `Initializing` branch sets `AuthState.Loading`. A test that sets the state right after
 * construction races that collector and can read `Loading` back. [awaitHandled] returns once
 * the collector's `emit` for the first value has returned, so the branch has run.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class HandledSessionStatus(initial: SessionStatus) : StateFlow<SessionStatus> {
    private val backing = MutableStateFlow(initial)
    private val handled = CompletableDeferred<Unit>()

    override val value: SessionStatus get() = backing.value
    override val replayCache: List<SessionStatus> get() = backing.replayCache

    override suspend fun collect(collector: FlowCollector<SessionStatus>): Nothing =
        backing.collect { status ->
            collector.emit(status)
            handled.complete(Unit)
        }

    suspend fun awaitHandled() = withTimeout(5.seconds) { handled.await() }
}
