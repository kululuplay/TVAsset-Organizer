package com.iptv.player.player.vod

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.player.VodPlaybackRoutingPolicy.ContentHint
import com.iptv.player.player.VodPlaybackRoutingPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackCoordinatorTest {

    @Test
    fun `auto exo and vlc preferences choose their expected initial routes`() {
        assertEquals(
            Route.EXO,
            startedRoute(selection(PlayerMode.AUTO, hint = ContentHint.MEDIA3_PREFERRED)),
        )
        assertEquals(
            Route.VLC_HARDWARE,
            startedRoute(selection(PlayerMode.AUTO, hint = ContentHint.VLC_COMPATIBILITY)),
        )
        assertEquals(
            Route.EXO,
            startedRoute(selection(PlayerMode.EXOPLAYER, hint = ContentHint.VLC_COMPATIBILITY)),
        )
        assertEquals(
            Route.VLC_HARDWARE,
            startedRoute(selection(PlayerMode.VLC, decoder = DecoderMode.HARDWARE)),
        )
        assertEquals(
            Route.VLC_SOFTWARE,
            startedRoute(selection(PlayerMode.VLC, decoder = DecoderMode.SOFTWARE)),
        )
    }

    @Test
    fun `remembered route overrides initial route only for auto auto`() {
        assertEquals(
            Route.VLC_SOFTWARE,
            startedRoute(
                selection(
                    player = PlayerMode.AUTO,
                    decoder = DecoderMode.AUTO,
                    hint = ContentHint.MEDIA3_PREFERRED,
                    rememberedRoute = Route.VLC_SOFTWARE,
                ),
            ),
        )
        assertEquals(
            Route.EXO,
            startedRoute(
                selection(
                    player = PlayerMode.EXOPLAYER,
                    decoder = DecoderMode.AUTO,
                    hint = ContentHint.VLC_COMPATIBILITY,
                    rememberedRoute = Route.VLC_SOFTWARE,
                ),
            ),
        )
        assertEquals(
            Route.VLC_HARDWARE,
            startedRoute(
                selection(
                    player = PlayerMode.AUTO,
                    decoder = DecoderMode.HARDWARE,
                    hint = ContentHint.VLC_COMPATIBILITY,
                    rememberedRoute = Route.EXO,
                ),
            ),
        )
        assertEquals(
            Route.VLC_SOFTWARE,
            startedRoute(
                selection(
                    player = PlayerMode.AUTO,
                    decoder = DecoderMode.SOFTWARE,
                    hint = ContentHint.MEDIA3_PREFERRED,
                    rememberedRoute = Route.EXO,
                ),
            ),
        )
        assertEquals(
            Route.VLC_HARDWARE,
            startedRoute(
                selection(
                    player = PlayerMode.VLC,
                    decoder = DecoderMode.AUTO,
                    hint = ContentHint.MEDIA3_PREFERRED,
                    rememberedRoute = Route.EXO,
                ),
            ),
        )
    }

    @Test
    fun `source failure retries same route then probes one alternate engine`() {
        val coordinator = VodPlaybackCoordinator()
        val first = start(coordinator)

        val retry = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(first.generation, retryableSourceFailure()),
        )
        val retryStart = retry.filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()
        assertEquals(first.route, retryStart.route)
        assertEquals(VodPlaybackCoordinator.StartReason.SOURCE_RETRY, retryStart.reason)
        assertEquals(first.generation + 1L, retryStart.generation)

        val fallback = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(
                retryStart.generation,
                retryableSourceFailure(),
            ),
        )
        val fallbackStart = fallback
            .filterIsInstance<VodPlaybackCoordinator.Action.Start>()
            .single()
        assertEquals(Route.VLC_HARDWARE, fallbackStart.route)
        assertEquals(VodPlaybackCoordinator.StartReason.ROUTE_FALLBACK, fallbackStart.reason)

        val terminal = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(
                fallbackStart.generation,
                retryableSourceFailure(),
            ),
        )
        assertEquals(1, terminal.filterIsInstance<VodPlaybackCoordinator.Action.TerminalFailure>().size)
        assertTrue(terminal.none { it is VodPlaybackCoordinator.Action.Start })
        assertEquals(
            VodPlaybackCoordinator.Phase.TERMINAL_FAILURE,
            coordinator.state.phase,
        )
    }

    @Test
    fun `remote switch can stop source recovery after same route retry`() {
        val coordinator = VodPlaybackCoordinator()
        val first = start(
            coordinator,
            selection(PlayerMode.AUTO).copy(allowSourceEngineFallback = false),
        )
        val retry = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(first.generation, retryableSourceFailure()),
        ).filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()

        val terminal = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(retry.generation, retryableSourceFailure()),
        )

        assertTrue(terminal.none { it is VodPlaybackCoordinator.Action.Start })
        assertEquals(VodPlaybackCoordinator.Phase.TERMINAL_FAILURE, coordinator.state.phase)
    }

    @Test
    fun `decoder failures traverse each auto route once then terminate`() {
        val coordinator = VodPlaybackCoordinator()
        val exo = start(coordinator)

        val hardware = fallbackStart(coordinator, exo.generation, decoderFailure())
        assertEquals(Route.VLC_HARDWARE, hardware.route)
        assertEquals(VodPlaybackCoordinator.StartReason.ROUTE_FALLBACK, hardware.reason)

        val software = fallbackStart(coordinator, hardware.generation, decoderFailure())
        assertEquals(Route.VLC_SOFTWARE, software.route)

        val terminal = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(software.generation, decoderFailure()),
        )
        assertTrue(terminal.none { it is VodPlaybackCoordinator.Action.Start })
        assertEquals(
            setOf(Route.EXO, Route.VLC_HARDWARE, Route.VLC_SOFTWARE),
            coordinator.state.triedRoutes,
        )
        assertEquals(VodPlaybackCoordinator.Phase.TERMINAL_FAILURE, coordinator.state.phase)
    }

    @Test
    fun `video output failure uses the same bounded fallback ladder`() {
        val coordinator = VodPlaybackCoordinator()
        val exo = start(coordinator)

        val hardware = fallbackStart(coordinator, exo.generation, outputFailure())
        assertEquals(Route.VLC_HARDWARE, hardware.route)
        val software = fallbackStart(coordinator, hardware.generation, outputFailure())
        assertEquals(Route.VLC_SOFTWARE, software.route)
    }

    @Test
    fun `explicit engine choices remain preferences with bounded rescue`() {
        val exoCoordinator = VodPlaybackCoordinator()
        val exo = start(exoCoordinator, selection(PlayerMode.EXOPLAYER))
        assertEquals(Route.EXO, exo.route)
        assertEquals(
            Route.VLC_HARDWARE,
            fallbackStart(exoCoordinator, exo.generation, decoderFailure()).route,
        )

        val vlcCoordinator = VodPlaybackCoordinator()
        val vlc = start(
            vlcCoordinator,
            selection(PlayerMode.VLC, decoder = DecoderMode.HARDWARE),
        )
        assertEquals(Route.VLC_HARDWARE, vlc.route)
        assertEquals(
            Route.VLC_SOFTWARE,
            fallbackStart(vlcCoordinator, vlc.generation, decoderFailure()).route,
        )
    }

    @Test
    fun `stale callbacks from a retired retry generation are rejected`() {
        val coordinator = VodPlaybackCoordinator()
        val first = start(coordinator)
        val retry = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(first.generation, retryableSourceFailure()),
        ).filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()
        val before = coordinator.state

        assertTrue(
            coordinator.dispatch(VodPlaybackCoordinator.Event.Ready(first.generation)).isEmpty(),
        )
        assertTrue(
            coordinator.dispatch(VodPlaybackCoordinator.Event.Completed(first.generation)).isEmpty(),
        )
        assertTrue(
            coordinator.dispatch(
                VodPlaybackCoordinator.Event.Failed(first.generation, decoderFailure()),
            ).isEmpty(),
        )
        assertEquals(before, coordinator.state)
        assertEquals(retry.generation, coordinator.state.generation)
    }

    @Test
    fun `rapid episode replacement stops old generation and rejects its completion`() {
        val coordinator = VodPlaybackCoordinator()
        val first = start(coordinator)
        val replacementActions = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Replace(
                selection(PlayerMode.AUTO, hint = ContentHint.VLC_COMPATIBILITY),
            ),
        )
        val stop = replacementActions.filterIsInstance<VodPlaybackCoordinator.Action.Stop>().single()
        val replacement = replacementActions
            .filterIsInstance<VodPlaybackCoordinator.Action.Start>()
            .single()

        assertEquals(first.generation, stop.generation)
        assertEquals(VodPlaybackCoordinator.StopReason.REPLACED, stop.reason)
        assertEquals(first.generation + 1L, replacement.generation)
        assertEquals(Route.VLC_HARDWARE, replacement.route)
        assertTrue(
            coordinator.dispatch(VodPlaybackCoordinator.Event.Completed(first.generation)).isEmpty(),
        )

        val ready = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Ready(replacement.generation),
        )
        assertEquals(1, ready.filterIsInstance<VodPlaybackCoordinator.Action.Ready>().size)
        assertEquals(VodPlaybackCoordinator.Phase.PLAYING, coordinator.state.phase)
    }

    @Test
    fun `completion is emitted once and all later terminal callbacks are ignored`() {
        val coordinator = VodPlaybackCoordinator()
        val start = start(coordinator)

        val completion = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Completed(start.generation),
        )
        assertEquals(1, completion.filterIsInstance<VodPlaybackCoordinator.Action.Completed>().size)
        assertEquals(VodPlaybackCoordinator.Phase.COMPLETED, coordinator.state.phase)
        assertTrue(
            coordinator.dispatch(VodPlaybackCoordinator.Event.Completed(start.generation)).isEmpty(),
        )
        assertTrue(
            coordinator.dispatch(
                VodPlaybackCoordinator.Event.Failed(start.generation, decoderFailure()),
            ).isEmpty(),
        )
    }

    @Test
    fun `do not retry failure goes directly to terminal action`() {
        val coordinator = VodPlaybackCoordinator()
        val start = start(coordinator)
        val failure = PlaybackFailure(
            category = PlaybackFailure.Category.AUTHORIZATION,
            code = PlaybackFailure.Code.HTTP_UNAUTHORIZED,
            component = PlaybackFailure.Component.TRANSPORT,
            retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
            httpStatus = 401,
        )

        val actions = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(start.generation, failure),
        )
        assertTrue(actions.none { it is VodPlaybackCoordinator.Action.Start })
        assertEquals(
            failure,
            actions.filterIsInstance<VodPlaybackCoordinator.Action.TerminalFailure>()
                .single()
                .failure,
        )
    }

    @Test
    fun `explicit stop invalidates active phase and ignores late callbacks`() {
        val coordinator = VodPlaybackCoordinator()
        val start = start(coordinator)

        val actions = coordinator.dispatch(VodPlaybackCoordinator.Event.Stop(start.generation))
        assertEquals(1, actions.filterIsInstance<VodPlaybackCoordinator.Action.Stop>().size)
        assertEquals(VodPlaybackCoordinator.Phase.IDLE, coordinator.state.phase)
        assertTrue(
            coordinator.dispatch(VodPlaybackCoordinator.Event.Ready(start.generation)).isEmpty(),
        )
    }

    private fun start(
        coordinator: VodPlaybackCoordinator,
        selection: VodPlaybackCoordinator.Selection = selection(PlayerMode.AUTO),
    ): VodPlaybackCoordinator.Action.Start = coordinator.dispatch(
        VodPlaybackCoordinator.Event.Replace(selection),
    ).filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()

    private fun startedRoute(selection: VodPlaybackCoordinator.Selection): Route =
        start(VodPlaybackCoordinator(), selection).route

    private fun fallbackStart(
        coordinator: VodPlaybackCoordinator,
        generation: Long,
        failure: PlaybackFailure,
    ): VodPlaybackCoordinator.Action.Start = coordinator.dispatch(
        VodPlaybackCoordinator.Event.Failed(generation, failure),
    ).filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()

    private fun selection(
        player: PlayerMode,
        decoder: DecoderMode = DecoderMode.AUTO,
        hint: ContentHint = ContentHint.MEDIA3_PREFERRED,
        rememberedRoute: Route? = null,
    ) = VodPlaybackCoordinator.Selection(player, decoder, hint, rememberedRoute)

    private fun retryableSourceFailure() = PlaybackFailure(
        category = PlaybackFailure.Category.NETWORK,
        code = PlaybackFailure.Code.CONNECTION_FAILED,
        phase = PlaybackFailure.Phase.PLAYBACK,
        component = PlaybackFailure.Component.TRANSPORT,
        retryAdvice = PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE,
    )

    private fun decoderFailure() = PlaybackFailure(
        category = PlaybackFailure.Category.DECODER,
        code = PlaybackFailure.Code.DECODER_RUNTIME_FAILED,
        phase = PlaybackFailure.Phase.PLAYBACK,
        component = PlaybackFailure.Component.VIDEO,
        retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_DECODER,
    )

    private fun outputFailure() = PlaybackFailure(
        category = PlaybackFailure.Category.OUTPUT,
        code = PlaybackFailure.Code.VIDEO_OUTPUT_FAILED,
        phase = PlaybackFailure.Phase.PLAYBACK,
        component = PlaybackFailure.Component.VIDEO,
        retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_DECODER,
    )
}
