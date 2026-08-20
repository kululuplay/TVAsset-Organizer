package com.iptv.player.player.vod

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.player.VodPlaybackRoutingPolicy
import com.iptv.player.player.VodPlaybackRoutingPolicy.Route

/**
 * Pure, URL-free VOD session reducer.
 *
 * The Activity keeps the actual media request outside this type and executes the
 * returned [Action]s. Every engine attempt receives a fresh [generation], so a
 * callback from a replaced episode, retired engine or same-route retry is ignored
 * without inspecting a content id, account id or URL.
 */
internal class VodPlaybackCoordinator(
    initialState: State = State(),
) {

    data class Selection(
        val playerMode: PlayerMode,
        val decoderMode: DecoderMode,
        val contentHint: VodPlaybackRoutingPolicy.ContentHint,
        /** Stable local suggestion; only AUTO/AUTO may override the base route. */
        val rememberedRoute: Route? = null,
        /** Remote safety switch for the one bounded source-engine compatibility try. */
        val allowSourceEngineFallback: Boolean = true,
    )

    enum class Phase {
        IDLE,
        STARTING,
        PLAYING,
        COMPLETED,
        TERMINAL_FAILURE,
    }

    data class State(
        val generation: Long = 0L,
        val phase: Phase = Phase.IDLE,
        val selection: Selection? = null,
        val route: Route? = null,
        val triedRoutes: Set<Route> = emptySet(),
        val sourceRetryCounts: Map<Route, Int> = emptyMap(),
        val sourceEngineFallbackAttempted: Boolean = false,
        val terminalFailure: PlaybackFailure? = null,
    )

    sealed interface Event {
        /** Start or rapidly replace the current movie/episode. */
        data class Replace(val selection: Selection) : Event

        data class Ready(val generation: Long) : Event

        data class Completed(val generation: Long) : Event

        data class Failed(
            val generation: Long,
            val failure: PlaybackFailure,
        ) : Event

        /** Lifecycle/user stop. The generation makes late stop requests harmless. */
        data class Stop(val generation: Long) : Event
    }

    sealed interface Action {
        /**
         * [generation] is the coordinator attempt token. The integration layer
         * maps the backend's own VodEngine generation back to this token; the two
         * counters must not be assumed to have equal numeric values.
         */
        data class Start(
            val generation: Long,
            val route: Route,
            val reason: StartReason,
        ) : Action

        data class Stop(
            val generation: Long,
            val route: Route,
            val reason: StopReason,
        ) : Action

        data class Ready(
            val generation: Long,
            val route: Route,
        ) : Action

        data class Completed(
            val generation: Long,
            val route: Route,
        ) : Action

        data class TerminalFailure(
            val generation: Long,
            val route: Route,
            val failure: PlaybackFailure,
        ) : Action
    }

    enum class StartReason {
        NEW_ITEM,
        SOURCE_RETRY,
        ROUTE_FALLBACK,
    }

    enum class StopReason {
        REPLACED,
        SOURCE_RETRY,
        ROUTE_FALLBACK,
        USER_OR_LIFECYCLE,
        TERMINAL_FAILURE,
    }

    data class Transition(
        val state: State,
        /** Execute in order: a retired attempt is always stopped before Start. */
        val actions: List<Action>,
    )

    var state: State = initialState
        private set

    fun dispatch(event: Event): List<Action> {
        val transition = reduce(state, event)
        state = transition.state
        return transition.actions
    }

    companion object {
        const val MAX_SOURCE_RETRIES_PER_ROUTE = 1

        fun reduce(state: State, event: Event): Transition = when (event) {
            is Event.Replace -> replace(state, event.selection)
            is Event.Ready -> ready(state, event.generation)
            is Event.Completed -> completed(state, event.generation)
            is Event.Failed -> failed(state, event.generation, event.failure)
            is Event.Stop -> stop(state, event.generation)
        }

        private fun replace(state: State, selection: Selection): Transition {
            val generation = nextGeneration(state.generation)
            val baseRoute = VodPlaybackRoutingPolicy.initialRoute(
                mode = selection.playerMode,
                decoderMode = selection.decoderMode,
                contentHint = selection.contentHint,
            )
            val route = selection.rememberedRoute
                ?.takeIf {
                    selection.playerMode == PlayerMode.AUTO &&
                        selection.decoderMode == DecoderMode.AUTO
                }
                ?: baseRoute
            val actions = buildList {
                state.route
                    ?.takeUnless { state.phase == Phase.TERMINAL_FAILURE }
                    ?.let { previousRoute ->
                        add(
                            Action.Stop(
                                generation = state.generation,
                                route = previousRoute,
                                reason = StopReason.REPLACED,
                            ),
                        )
                    }
                add(Action.Start(generation, route, StartReason.NEW_ITEM))
            }
            return Transition(
                state = State(
                    generation = generation,
                    phase = Phase.STARTING,
                    selection = selection,
                    route = route,
                    triedRoutes = setOf(route),
                ),
                actions = actions,
            )
        }

        private fun ready(state: State, generation: Long): Transition {
            if (
                !state.isCurrent(generation) ||
                state.phase != Phase.STARTING
            ) {
                return unchanged(state)
            }
            val route = state.route ?: return unchanged(state)
            return Transition(
                state = state.copy(phase = Phase.PLAYING),
                actions = listOf(Action.Ready(generation, route)),
            )
        }

        private fun completed(state: State, generation: Long): Transition {
            if (
                !state.isCurrent(generation) ||
                state.phase !in ACTIVE_PHASES
            ) {
                return unchanged(state)
            }
            val route = state.route ?: return unchanged(state)
            return Transition(
                state = state.copy(phase = Phase.COMPLETED),
                actions = listOf(Action.Completed(generation, route)),
            )
        }

        private fun failed(
            state: State,
            generation: Long,
            failure: PlaybackFailure,
        ): Transition {
            if (
                !state.isCurrent(generation) ||
                state.phase !in ACTIVE_PHASES
            ) {
                return unchanged(state)
            }
            val selection = state.selection ?: return unchanged(state)
            val route = state.route ?: return unchanged(state)
            val routingFailure = VodPlaybackRoutingPolicy.routeFailure(failure)

            if (routingFailure == VodPlaybackRoutingPolicy.Failure.SOURCE) {
                if (failure.retryAdvice == PlaybackFailure.RetryAdvice.DO_NOT_RETRY) {
                    return terminal(state, failure)
                }
                // The alternate engine is a one-shot compatibility probe. It is
                // not retried again, keeping the provider connection ladder small.
                if (state.sourceEngineFallbackAttempted) return terminal(state, failure)
                val retries = state.sourceRetryCounts[route] ?: 0
                if (
                    retries < MAX_SOURCE_RETRIES_PER_ROUTE
                ) {
                    return startAttempt(
                        state = state.copy(
                            sourceRetryCounts = state.sourceRetryCounts + (route to (retries + 1)),
                        ),
                        route = route,
                        startReason = StartReason.SOURCE_RETRY,
                        stopReason = StopReason.SOURCE_RETRY,
                    )
                }
                if (!selection.allowSourceEngineFallback) return terminal(state, failure)
                val nextRoute = VodPlaybackRoutingPolicy.nextRoute(
                    mode = selection.playerMode,
                    decoderMode = selection.decoderMode,
                    current = route,
                    failure = routingFailure,
                    tried = state.triedRoutes,
                ) ?: return terminal(state, failure)
                return startAttempt(
                    state = state.copy(
                        triedRoutes = state.triedRoutes + nextRoute,
                        sourceEngineFallbackAttempted = true,
                    ),
                    route = nextRoute,
                    startReason = StartReason.ROUTE_FALLBACK,
                    stopReason = StopReason.ROUTE_FALLBACK,
                )
            }

            val nextRoute = VodPlaybackRoutingPolicy.nextRoute(
                mode = selection.playerMode,
                decoderMode = selection.decoderMode,
                current = route,
                failure = routingFailure,
                tried = state.triedRoutes,
            ) ?: return terminal(state, failure)

            return startAttempt(
                state = state.copy(triedRoutes = state.triedRoutes + nextRoute),
                route = nextRoute,
                startReason = StartReason.ROUTE_FALLBACK,
                stopReason = StopReason.ROUTE_FALLBACK,
            )
        }

        private fun startAttempt(
            state: State,
            route: Route,
            startReason: StartReason,
            stopReason: StopReason,
        ): Transition {
            val previousRoute = state.route ?: return unchanged(state)
            val previousGeneration = state.generation
            val generation = nextGeneration(previousGeneration)
            return Transition(
                state = state.copy(
                    generation = generation,
                    phase = Phase.STARTING,
                    route = route,
                    terminalFailure = null,
                ),
                actions = listOf(
                    Action.Stop(previousGeneration, previousRoute, stopReason),
                    Action.Start(generation, route, startReason),
                ),
            )
        }

        private fun terminal(state: State, failure: PlaybackFailure): Transition {
            val route = state.route ?: return unchanged(state)
            return Transition(
                state = state.copy(
                    phase = Phase.TERMINAL_FAILURE,
                    terminalFailure = failure,
                ),
                actions = listOf(
                    Action.Stop(state.generation, route, StopReason.TERMINAL_FAILURE),
                    Action.TerminalFailure(state.generation, route, failure),
                ),
            )
        }

        private fun stop(state: State, generation: Long): Transition {
            if (!state.isCurrent(generation)) return unchanged(state)
            val route = state.route ?: return unchanged(state)
            if (state.phase == Phase.TERMINAL_FAILURE) {
                return Transition(State(generation = state.generation), emptyList())
            }
            return Transition(
                state = State(generation = state.generation),
                actions = listOf(
                    Action.Stop(generation, route, StopReason.USER_OR_LIFECYCLE),
                ),
            )
        }

        private fun State.isCurrent(candidate: Long): Boolean =
            candidate == generation && route != null

        private fun unchanged(state: State): Transition = Transition(state, emptyList())

        private fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L

        private val ACTIVE_PHASES = setOf(Phase.STARTING, Phase.PLAYING)
    }
}
