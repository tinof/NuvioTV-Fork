package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultAllocatorNative
import com.nuvio.tv.core.performance.DeviceProfile
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.core.util.DebugSwitches
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper

/**
 * Shared memory budget constants and helpers for buffer + parallel connection settings.
 * Used by both PlaybackSettingsViewModel and PlaybackBufferNetworkSettings UI.
 */
@UnstableApi
object MemoryBudget {
    const val TAG = "MemoryBudget"

    // Heap-tiered budget ratios. Low-RAM devices (Fire TV class) need more
    // headroom for codec/surface/UI; high-RAM devices can dedicate more to buffering.
    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    // The buffer allocator is on the Java heap; on low-RAM reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L
    // Not the native arena's chunk size, so the probe leaves the shared arena alone.
    private const val NATIVE_PROBE_BYTES = 4096

    // Conversion overhead tracks the video rather than the device, so these only hold back a margin;
    // high-RAM keeps a larger one because it still retains a back buffer during conversion.
    private const val LOW_RAM_CONVERSION_RATIO = 0.95f
    private const val HIGH_RAM_CONVERSION_RATIO = 0.75f

    /** ParallelRangeDataSource schedules maxAhead = parallelConnections + 1 chunks concurrently */
    // Matches ParallelRangeDataSource: the session holds up to
    // connections + 2 chunks on low-RAM devices (connections + 4 on high-RAM;
    // the extra headroom is deliberately not advertised so the enforcement
    // maths stays conservative).
    private const val BUFFER_OVERHEAD = 2

    // ParallelRangeDataSource's live prefetch window is clamped to at most
    // 4*connections chunks (sized from a device-RAM budget). The DISPLAYED memory
    // estimate uses this upper bound so the "estimated usage" warning over-estimates
    // rather than under-estimates, the safe direction for a guard.
    // These clamp multiples are the SINGLE SOURCE OF TRUTH for the deep-path
    // window, shared by the runtime depth (PlayerMediaSourceFactory delegates to
    // prefetchDepthChunks below) and the displayed estimate, so the two can never
    // drift. bufferCount and the enforcement maths (maxChunkMb / maxBufferMb / enforce)
    // are deliberately left on the conservative connections+2 accounting so the slider
    // maxima are unchanged.
    private const val PREFETCH_DEPTH_LOWER_MULTIPLE = 2
    private const val PREFETCH_DEPTH_UPPER_MULTIPLE = 4

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
    const val MAX_CONNECTIONS_NATIVE_MEMORY = PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT_NATIVE_MEMORY
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_KB / 1024
    // Low-RAM devices (1-2 GB class): the session's protected set (playhead
    // window + tail moov chunks + pinned side chunks) puts the worst-case
    // retained floor at roughly connections + 10 chunks on a scatter-heavy
    // file, so chunk size is hard-capped at 16 MB on boxes under 2.3 GB; above it
    // the floor alone exceeds what those devices can hold. Applies in
    // performance mode too; the cap is a tier property, not a budget one.
    private const val LOW_RAM_MAX_CHUNK_MB = 16
    // Without native memory, connections x chunk stays within this fraction (1/N) of the heap.
    private const val HEAP_CHUNK_SHARE = 4
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    // Half the device default floors the slider above what a low-RAM stick can sustain.
    const val MIN_TARGET_BUFFER_MB = 50
    private const val MIN_PLAYBACK_BUDGET_MB = 100
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    // The custom buffers and the parallel chunks use native memory whenever the engine's allocator
    // library loads, so the budget then follows device RAM like the native engine does.
    val offHeapBuffers: Boolean = runCatching {
        DefaultAllocatorNative.createAllocation(NATIVE_PROBE_BYTES)
            ?.also(DefaultAllocatorNative::freeAllocation) != null
    }.getOrDefault(false)

    private val deviceRamBytes: Long = NuvioExoPlayerPerformanceHelper.getRamFromMemInfo()

    /**
     * The heap rule alone, for memory that stays on the Java heap even with off-heap buffers: the parallel chunk
     * ceiling ([tierMaxChunkMbFor]) and the trailer pool.
     */
    internal val lowHeap: Boolean = maxHeapMb < HIGH_HEAP_THRESHOLD_MB

    /**
     * True on the Fire TV / TV-stick class: the app heap below the high-RAM threshold, or on Nvidia with off-heap
     * buffers, device RAM below 2.3 GB. Declared after [offHeapBuffers] and [deviceRamBytes], which it reads.
     */
    val isLowRamTier: Boolean = lowRamTierFor(
        maxHeapMb = maxHeapMb,
        offHeap = offHeapBuffers,
        deviceRamBytes = deviceRamBytes,
        switch = DebugSwitches.flag("debug.nuvio.lowram_from_ram"),
    )

    /**
     * The tier from the Java heap, except on Nvidia with off-heap buffers: the buffers then do not live on the heap,
     * so device RAM decides ([DeviceProfile.RamClass.SMALL] is low). RAM not known falls back to the heap.
     * [switch] (debug.nuvio.lowram_from_ram): true decides from device RAM on any box with off-heap buffers, false
     * always from the heap.
     */
    internal fun lowRamTierFor(
        maxHeapMb: Long,
        offHeap: Boolean,
        deviceRamBytes: Long,
        switch: Boolean?,
        manufacturer: String? = Build.MANUFACTURER,
    ): Boolean {
        val heapLow = maxHeapMb < HIGH_HEAP_THRESHOLD_MB
        if (!offHeap || !(switch ?: DeviceProfile.isNvidia(manufacturer))) return heapLow
        return when (DeviceProfile.ramClass(deviceRamBytes)) {
            DeviceProfile.RamClass.UNKNOWN -> heapLow
            DeviceProfile.RamClass.SMALL -> true
            DeviceProfile.RamClass.MID, DeviceProfile.RamClass.LARGE -> false
        }
    }

    val budgetMb: Int = budgetMbFor(offHeapBuffers, deviceRamBytes, maxHeapMb)

    internal fun budgetMbFor(offHeap: Boolean, deviceRamBytes: Long, maxHeapMb: Long): Int {
        if (offHeap) return NuvioExoPlayerPerformanceHelper.safeNativeMemoryLimitMbForRam(deviceRamBytes)
        val lowRam = maxHeapMb < HIGH_HEAP_THRESHOLD_MB
        // Pre-cap ratio budget, before the low-RAM reserve trims it below.
        val rawBudgetMb = (maxHeapMb * (if (lowRam) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()
        if (!lowRam) return rawBudgetMb
        // The flat reserve collapses the budget on small heaps, so hold a floor that still leaves
        // the smallest devices half their heap.
        val minPlaybackBudgetMb =
            MIN_PLAYBACK_BUDGET_MB.coerceAtMost((maxHeapMb / 2).toInt()).coerceAtLeast(MIN_BUFFER_MB)
        return rawBudgetMb.coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt()).coerceAtLeast(minPlaybackBudgetMb)
    }

    // budgetMb is the most the settings allow; the managed budget uses the box's automatic size instead.
    val autoBudgetMb: Int = autoBudgetMbFor(offHeapBuffers, deviceRamBytes, budgetMb)

    internal fun autoBudgetMbFor(offHeap: Boolean, deviceRamBytes: Long, budgetMb: Int): Int =
        if (offHeap) BufferSizing.autoBufferMbForRam(deviceRamBytes).coerceAtMost(budgetMb) else budgetMb

    fun conversionBudgetMb(budgetMb: Int): Int =
        (budgetMb * (if (isLowRamTier) LOW_RAM_CONVERSION_RATIO else HIGH_RAM_CONVERSION_RATIO)).toInt()
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    /** Number of chunk-sized buffers alive concurrently */
    fun bufferCount(connectionCount: Int): Int =
        connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    /**
     * Deep-path prefetch depth, the SINGLE SOURCE OF TRUTH shared by the runtime
     * (PlayerMediaSourceFactory.computePrefetchDepthChunks delegates here) and the
     * displayed memory estimate. Spend the safe
     * native budget minus the SampleQueue reserve on chunks, divide by chunk size, clamp
     * to [2*connections, 4*connections].
     */
    fun prefetchDepthChunks(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
    ): Int {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        val chunkBudgetMb = (safeNativeLimitMb - reserveBufferMb.coerceAtLeast(0))
            .coerceAtLeast(chunkMb * PREFETCH_DEPTH_LOWER_MULTIPLE)
        val byBudget = chunkBudgetMb / chunkMb
        return byBudget.coerceIn(
            connections * PREFETCH_DEPTH_LOWER_MULTIPLE,
            connections * PREFETCH_DEPTH_UPPER_MULTIPLE
        )
    }

    /**
     * How many chunks playback keeps in flight from the reader: connections + 1,
     * or the [prefetchDepthChunks] window in performance mode. The player, the
     * settings estimate and the speed test all size their downloads from this.
     */
    data class DownloadWindow(
        val performanceMode: Boolean,
        val safeNativeLimitMb: Int,
        val reserveBufferMb: Int
    ) {
        fun prefetchDepth(connections: Int, chunkSizeMb: Int): Int =
            if (performanceMode) {
                prefetchDepthChunks(connections, chunkSizeMb, safeNativeLimitMb, reserveBufferMb)
            } else {
                connections + 1
            }
    }

    // Assessment-sweep cell budgeting
    //
    // A sweep cell's true memory ceiling is the tester's prefetch WINDOW, not
    // connections x chunk: ParallelRangeDataSource may hold up to its prefetch
    // depth in live chunks (plus the memory-tiered session-cap headroom). An
    // unconditional connections*4 window would give a 3 conn / 64 MB cell a
    // ceiling near 1 GB on a box with a 250 MB budget.
    //
    // SWEEP_BUDGET_FRACTION reserves headroom for the app's own native use
    // while a sweep runs; SWEEP_CELL_MAX_CONCURRENT_MB is the absolute cap on
    // the nominal concurrent-download footprint (connections x chunk) on ANY
    // device: 3 x 64 MB never runs, on the 4 GB tier included. Both are
    // estimates, tunable like the trade constants.
    private const val SWEEP_BUDGET_FRACTION = 0.75
    const val SWEEP_CELL_MAX_CONCURRENT_MB = 128

    /**
     * Prefetch depth (in chunks) a sweep cell runs with on this device, or
     * null when the cell must NOT be scheduled at all. The depth is the one
     * playback would use for the same cell ([DownloadWindow.prefetchDepth]),
     * so the test keeps as many downloads running as playback does. Null
     * when either the nominal concurrent footprint (connections x chunk)
     * exceeds [SWEEP_CELL_MAX_CONCURRENT_MB], or that window does not fit the
     * sweep's share of the safe native budget.
     * Single source of truth: the sweep's ladder gate and the tester's window
     * both use this value, so the gate can never drift from the allocation.
     */
    fun sweepCellPrefetchDepth(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        playback: DownloadWindow
    ): Int? {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        if (connections * chunkMb > SWEEP_CELL_MAX_CONCURRENT_MB) return null
        val budgetMb = (safeNativeLimitMb * SWEEP_BUDGET_FRACTION).toInt()
        val depth = playback.prefetchDepth(connections, chunkMb)
        return depth.takeIf { it <= budgetMb / chunkMb }
    }

    /**
     * Parallel overhead for the DISPLAYED memory-usage estimate only. Rather than
     * always assuming the 4*connections upper clamp (which over-states usage whenever
     * the device budget clamps the window lower, and makes the per-step deltas look
     * inflated), this computes the ACTUAL depth the runtime will use via
     * [prefetchDepthChunks], plus the session's live-chunk headroom (depth + 2 on
     * low-RAM, depth + 4 on high-RAM, mirroring
     * ParallelRangeDataSource.sessionChunkCap). When the deep path is inactive
     * (performance mode off, or MP4 session mode) the runtime window is connections + 1
     * and the estimate says so. Unlike [parallelOverheadMb] (which underpins the slider
     * maxima and must stay conservative), this is display-only.
     */
    fun displayParallelOverheadMb(
        connectionCount: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
        deepPathActive: Boolean,
    ): Int {
        val depth = DownloadWindow(deepPathActive, safeNativeLimitMb, reserveBufferMb)
            .prefetchDepth(connectionCount, chunkSizeMb)
        val sessionHeadroom = if (isLowRamTier) 2 else 4
        return (depth + sessionHeadroom) * chunkSizeMb
    }

    /** Total usage for the DISPLAYED estimate: buffer plus the exact parallel overhead. */
    fun displayTotalUsageMb(
        bufferMb: Int,
        connectionCount: Int,
        chunkSizeMb: Int,
        parallelEnabled: Boolean,
        safeNativeLimitMb: Int,
        deepPathActive: Boolean,
    ): Int =
        bufferMb + if (parallelEnabled) {
            displayParallelOverheadMb(connectionCount, chunkSizeMb, safeNativeLimitMb, bufferMb, deepPathActive)
        } else {
            0
        }

    /** Hard chunk-size ceiling for this device; binds everywhere, including performance mode. */
    fun tierMaxChunkMb(nativeMemory: Boolean, connections: Int): Int =
        tierMaxChunkMbFor(lowHeap, offHeapBuffers, deviceRamBytes, maxHeapMb, nativeMemory, connections)

    /**
     * Under 2.3 GB of RAM, or with chunks on the heap, a small heap keeps 16 MB; above it the ceiling follows RAM with
     * native memory and a quarter of the heap per connection without it.
     */
    internal fun tierMaxChunkMbFor(
        lowHeap: Boolean,
        offHeap: Boolean,
        deviceRamBytes: Long,
        maxHeapMb: Long,
        nativeMemory: Boolean,
        connections: Int,
    ): Int {
        if (!lowHeap) return MAX_CHUNK_MB
        if (BufferSizing.isSmallBox(deviceRamBytes) || !offHeap) return LOW_RAM_MAX_CHUNK_MB
        if (offHeap && nativeMemory) return MAX_CHUNK_MB
        val heapShareMb = (maxHeapMb / HEAP_CHUNK_SHARE).toInt()
        return chunkStepAtOrBelow(heapShareMb / connections.coerceAtLeast(1)).coerceAtLeast(LOW_RAM_MAX_CHUNK_MB)
    }

    fun maxConnections(nativeMemory: Boolean): Int = PlayerSettings.maxParallelConnectionCount(nativeMemory)

    /** The chunk sizes from 8 MB up that the Chunk size setting offers. */
    val CHUNK_STEPS_MB = listOf(8, 16, 24, 32, 48, 64, 96, 128)

    /** The largest offered chunk size at or below [mb], never below [MIN_CHUNK_MB]. */
    fun chunkStepAtOrBelow(mb: Int): Int = CHUNK_STEPS_MB.lastOrNull { it <= mb } ?: MIN_CHUNK_MB

    /**
     * With native memory: the largest offered chunk size whose download window, as [displayParallelOverheadMb] works
     * it out, stays within [limitMb]. Never above [tierMaxMb], never below [MIN_CHUNK_MB].
     */
    fun nativeMaxChunkMb(
        connections: Int,
        limitMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
        tierMaxMb: Int = tierMaxChunkMb(nativeMemory = true, connections),
    ): Int = CHUNK_STEPS_MB.filter { it <= tierMaxMb }.lastOrNull {
        displayParallelOverheadMb(connections, it, safeNativeLimitMb, reserveBufferMb, deepPathActive = true) <= limitMb
    } ?: MIN_CHUNK_MB

    /**
     * The largest chunk size Custom network uses with [settings]. With native memory the download window stays within
     * the box's warning limit, worked out with the target the player uses; without it the chunks fit the budget beside
     * the buffer, as the Chunk size setting allows. A larger saved size is kept and used again once it fits.
     */
    fun customMaxChunkMb(
        settings: PlayerSettings,
        autoBufferMb: Int,
        safeNativeLimitMb: Int,
        warningLimitMb: Int,
        tierMaxMb: Int = tierMaxChunkMb(settings.nuvioPerformanceModeEnabled, settings.parallelConnectionCount),
    ): Int {
        if (!settings.nuvioPerformanceModeEnabled) {
            val bufferMb = effectiveBufferMb(settings.bufferSettings.targetBufferSizeMb)
            return chunkStepAtOrBelow(maxChunkMb(bufferMb, settings.parallelConnectionCount, tierMaxMb))
        }
        val userTarget = settings.bufferEngineEnabled && !settings.bufferBudgetManaged
        if (!userTarget) {
            return nativeMaxChunkMb(settings.parallelConnectionCount, warningLimitMb, autoBufferMb, autoBufferMb, tierMaxMb)
        }
        val storedMb = settings.bufferSettings.targetBufferSizeMb
        val targetMb = if (!settings.allowLargeTargetBuffer && storedMb > safeNativeLimitMb) safeNativeLimitMb else storedMb
        return nativeMaxChunkMb(settings.parallelConnectionCount, warningLimitMb, safeNativeLimitMb, targetMb, tierMaxMb)
    }

    fun customMaxChunkMb(context: Context, settings: PlayerSettings): Int = customMaxChunkMb(
        settings,
        NuvioExoPlayerPerformanceHelper.getAutoBufferMb(context),
        NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context),
        NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
    )

    /** The chunk size playback uses: the saved one, no larger than [customMaxChunkMb]. */
    fun customChunkKb(context: Context, settings: PlayerSettings): Int =
        settings.parallelChunkSizeKb.coerceAtMost(customMaxChunkMb(context, settings) * 1024)

    /** Max chunk size that fits budget given current buffer size */
    fun maxChunkMb(
        bufferMb: Int,
        connectionCount: Int,
        tierMaxMb: Int = tierMaxChunkMb(nativeMemory = false, connectionCount),
    ): Int = ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, tierMaxMb)

    /** Max buffer size that fits budget given current parallel overhead */
    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    /** Slider max for target buffer, optionally raised to 2GB when override is on. */
    fun maxBufferMbWithOverride(parallelOverheadMb: Int, allowLargeTargetBuffer: Boolean): Int {
        val safeMax = maxBufferMb(parallelOverheadMb)
        return if (allowLargeTargetBuffer) {
            PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
                .coerceAtMost(MAX_BUFFER_MB)
                .coerceAtLeast(safeMax)
        } else {
            safeMax
        }
    }

    /**
     * Enforce budget: reduce chunk first, then buffer as last resort.
     * Returns (adjustedBufferMb, adjustedChunkMb).
     */
    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val newChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * newChunkMb <= budgetMb) return bufferMb to newChunkMb

        // Even min chunk doesn't fit, also reduce buffer
        val newBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return newBufferMb to MIN_CHUNK_MB
    }

    /**
     * Determines the memory usage status based on total usage, safe limit, and warning limit.
     */
    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus {
        return when {
            totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
            totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}

