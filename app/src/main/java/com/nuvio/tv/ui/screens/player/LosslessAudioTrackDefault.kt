package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import kotlinx.coroutines.flow.update

/**
 * Defaults the audio selection to the best lossless track, since the engines'
 * own defaults follow container flags and language and often land on the lossy
 * companion track of a remux.
 *
 * Precedence: an explicit pick this session, a remembered per-title selection,
 * an engine-failover carry-over, this default, then the engine's own default.
 *
 * The preference applies only within the first preferred language that has any
 * track; a lossless track is never chosen over the user's language. A track is
 * ranked only when its codec or name proves the tier (mpv reports plain "dts",
 * so it needs an MA / Master Audio hint; ExoPlayer's DTS-HD mime ranks as
 * lossless). The default is never persisted.
 *
 * With a passthrough policy, a TrueHD or DTS-HD track the policy would decode to PCM
 * (Atmos and DTS:X objects lost) ranks below a lower lossless tier the policy lets out,
 * then below a lossy track it would bitstream, E-AC-3 JOC first. FLAC and PCM are always
 * decoded and keep their rank. The policy is checked against the format's own mime when
 * the engine reports one (ExoPlayer), else against the mime the label implies (mpv).
 */
internal object LosslessAudioTrackDefault {

    /** Ranking tiers, higher is better. Mirrors the StreamQualityRank ordering. */
    const val TIER_TRUEHD = 4
    const val TIER_DTS_HD_MA = 3
    const val TIER_FLAC = 2
    const val TIER_PCM = 1

    private val commentaryHints = listOf(
        "commentary", "descriptive", "description", "narration", "narrator"
    )

    /**
     * Lossless tier of a track, or null when the track is not provably lossless.
     * [codec] is TrackInfo.codec: ExoPlayer supplies CustomDefaultTrackNameProvider
     * names ("TrueHD", "DTS-HD", "FLAC", "ALAC", "WAV", "PCM"); mpv supplies FFmpeg
     * codec names ("truehd", "mlp", "dts", "flac", "pcm_s24le", ...).
     * [name] is the display name, used for MA hints and PCM/LPCM hints only.
     */
    fun losslessTier(codec: String?, name: String?): Int? {
        val c = codec?.trim()?.lowercase().orEmpty()
        val n = name?.trim()?.lowercase().orEmpty()

        if (c == "truehd" || c == "mlp" || "truehd" in n || "true hd" in n) return TIER_TRUEHD

        val nameSaysMa =
            "dts-hd ma" in n || "dts hd ma" in n || "dtshd ma" in n || "master audio" in n ||
                Regex("\\bma\\b").containsMatchIn(n)
        if (c == "dts-hd") return TIER_DTS_HD_MA
        if (c == "dts" && nameSaysMa) return TIER_DTS_HD_MA
        if (c.isEmpty() && nameSaysMa && "dts" in n) return TIER_DTS_HD_MA

        if (c == "flac" || c == "alac" || "flac" in n) return TIER_FLAC

        if (c == "pcm" || c == "wav" || c.startsWith("pcm_") || "lpcm" in n) return TIER_PCM

        return null
    }

    fun isCommentaryRole(role: TrackRole?): Boolean =
        role == TrackRole.COMMENTARY || role == TrackRole.AUDIO_DESCRIPTION

    fun isCommentaryLike(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return commentaryHints.any { it in n }
    }

    /**
     * Index of the track the default should select, or null when the engine's own pick
     * should stand. [languageMatches] is (trackLanguage, preferredLanguage) -> Boolean,
     * expected to tolerate ISO 639-1/639-2 variants (PlayerSubtitleUtils.matchesLanguageCode).
     */
    fun pickDefaultIndex(
        tracks: List<TrackInfo>,
        preferredLanguages: List<String>,
        languageMatches: (String?, String) -> Boolean,
        passthroughPolicy: AudioPassthroughPolicy? = null
    ): Int? {
        if (tracks.isEmpty()) return null

        // Candidate pool: the first preferred language that has any track; if no
        // preferred language matches anything, the whole set.
        val pool = preferredLanguages
            .asSequence()
            .map { lang -> tracks.filter { languageMatches(it.language, lang) } }
            .firstOrNull { it.isNotEmpty() }
            ?: tracks

        val ranked = pool.mapNotNull { track ->
            losslessTier(track.codec, track.name)?.let { tier -> track to tier }
        }
        if (ranked.isEmpty()) return null

        // Highest tier that has a non-commentary member; a commentary track is never
        // made the default, even when it is the only lossless one. A pick the policy would
        // decode is kept as the fallback while lower tiers are tried: a DTS-HD MA track that
        // is bitstreamed beats a decoded TrueHD one, and FLAC/PCM are never denied.
        val byTierDesc = ranked.groupBy({ it.second }, { it.first })
            .toSortedMap(compareByDescending { it })
        var decodedFallback: TrackInfo? = null
        for ((_, tierTracks) in byTierDesc) {
            val clean = tierTracks
                .filterNot { isCommentaryLike(it.name) || isCommentaryRole(it.role) }
                .sortedWith(
                    compareByDescending<TrackInfo> { it.channelCount ?: 0 }
                        .thenBy { it.index }
                )
            if (clean.isEmpty()) continue
            val lossless = clean.firstOrNull { passthroughPolicy?.deniesPassthrough(it.policyMime()) != true }
            if (lossless != null) return lossless.index
            if (decodedFallback == null) decodedFallback = clean.first()
        }
        val decoded = decodedFallback ?: return null
        // Every lossless pick would be decoded: a lossy track the policy bitstreams wins.
        passthroughPolicy?.let { policy -> bitstreamedLossy(pool, policy)?.let { return it.index } }
        return decoded.index
    }

    /** The format's own mime when the engine reports it (ExoPlayer), else the one the label implies (mpv). */
    private fun TrackInfo.policyMime(): String? = sampleMimeType ?: passthroughMime(codec, name)

    /** Lossy rank when bitstreamed, higher is better; null when the codec has no passthrough. */
    private fun lossyBitstreamRank(mime: String?): Int? = when (mime) {
        MimeTypes.AUDIO_E_AC3_JOC -> 4
        MimeTypes.AUDIO_E_AC3 -> 3
        MimeTypes.AUDIO_DTS -> 2
        MimeTypes.AUDIO_AC3 -> 1
        else -> null
    }

    /** Best non-commentary lossy track in [pool] that [policy] lets out as bitstream. */
    private fun bitstreamedLossy(pool: List<TrackInfo>, policy: AudioPassthroughPolicy): TrackInfo? =
        pool.asSequence()
            .filterNot { isCommentaryLike(it.name) || isCommentaryRole(it.role) }
            .filter { losslessTier(it.codec, it.name) == null }
            .mapNotNull { track ->
                val mime = track.policyMime()
                val rank = lossyBitstreamRank(mime) ?: return@mapNotNull null
                if (policy.deniesPassthrough(mime)) null else track to rank
            }
            .sortedWith(
                compareByDescending<Pair<TrackInfo, Int>> { it.second }
                    .thenByDescending { it.first.channelCount ?: 0 }
                    .thenBy { it.first.index }
            )
            .firstOrNull()
            ?.first

    /**
     * Passthrough mime for a track label: ExoPlayer names ("TrueHD", "DTS-HD", "E-AC-3-JOC",
     * "AC-3") or FFmpeg names from mpv ("truehd", "dts", "eac3", "ac3"). Null otherwise.
     */
    internal fun passthroughMime(codec: String?, name: String?): String? {
        val c = codec?.trim()?.lowercase().orEmpty()
        return when (c) {
            "truehd", "mlp" -> MimeTypes.AUDIO_TRUEHD
            "dts-hd" -> MimeTypes.AUDIO_DTS_HD
            "dts" -> if (losslessTier(codec, name) == TIER_DTS_HD_MA) MimeTypes.AUDIO_DTS_HD else MimeTypes.AUDIO_DTS
            "e-ac-3-joc" -> MimeTypes.AUDIO_E_AC3_JOC
            "e-ac-3", "eac3" -> MimeTypes.AUDIO_E_AC3
            "ac-3", "ac3" -> MimeTypes.AUDIO_AC3
            else -> null
        }
    }
}

/**
 * Applies the lossless default once per stream, after the persisted/remembered restore
 * pass has had its chance. Runs on both engines' tracks-ready paths; selection goes
 * through the same selectAudioTrack() the restore path uses, so engine handling and the
 * failover carry-over capture see it exactly like any other selection. Never persisted.
 */
internal fun PlayerRuntimeController.applyLosslessAudioDefaultIfUnset(
    audioTracks: List<TrackInfo>
) {
    if (audioTracks.isEmpty()) return
    if (losslessAudioDefaultAppliedForStream) return

    val rememberedAudio = rememberedTrackPreference?.audio
    val persistedAudio = persistedTrackPreference?.audio
    if (rememberedAudio != null || persistedAudio != null ||
        persistedAudioPreferenceSeenForStream ||
        pendingEngineSwitchTrackPreference != null
    ) {
        losslessAudioDefaultAppliedForStream = true
        logSwitchTrace(
            stage = "lossless-default",
            message = "result=skip reason=preference-present remembered=${rememberedAudio != null} " +
                "persisted=${persistedAudio != null} seen=$persistedAudioPreferenceSeenForStream " +
                "switchPending=${pendingEngineSwitchTrackPreference != null}"
        )
        return
    }

    losslessAudioDefaultAppliedForStream = true

    // The policy is ExoPlayer's sink policy; mpv has its own passthrough handling.
    val pick = LosslessAudioTrackDefault.pickDefaultIndex(
        tracks = audioTracks,
        preferredLanguages = mpvPreferredAudioLanguages,
        languageMatches = { trackLanguage, preferred ->
            PlayerSubtitleUtils.matchesLanguageCode(trackLanguage, preferred)
        },
        passthroughPolicy = if (isUsingMpvEngine()) null else currentAudioPassthroughPolicy
    )
    val selectedIndex = audioTracks.indexOfFirst { it.isSelected }
        .takeIf { it >= 0 } ?: _uiState.value.selectedAudioTrackIndex
    if (pick == null || pick == selectedIndex) {
        logSwitchTrace(
            stage = "lossless-default",
            message = "result=${if (pick == null) "none" else "already-selected"} " +
                "pick=$pick selected=$selectedIndex " +
                "tracks=${audioTracks.joinToString { "${it.index}:${it.codec}/${it.language}" }}"
        )
        return
    }

    val target = audioTracks[pick]
    logSwitchTrace(
        stage = "lossless-default",
        message = "result=apply index=$pick codec=${target.codec} lang=${target.language} " +
            "name=${target.name} channels=${target.channelCount} was=$selectedIndex"
    )
    selectAudioTrack(pick)
    _uiState.update { it.copy(selectedAudioTrackIndex = pick) }
}
