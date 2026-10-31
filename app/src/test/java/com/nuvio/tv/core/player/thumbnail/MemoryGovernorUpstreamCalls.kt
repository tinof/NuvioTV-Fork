package com.nuvio.tv.core.player.thumbnail

/**
 * The shapes upstream's tests call, which know no swapRoom. These live in test sources on purpose:
 * main code has no call without swapRoom, so a production path that omits the field does not compile.
 */
internal fun MemoryGovernor.pressureReason(
    lowMemory: Boolean, availMb: Long, swapTotalMb: Long, swapFreeMb: Long, floorMb: Long, strongHardware: Boolean = false,
): String? = pressureReason(lowMemory, availMb, swapTotalMb, swapFreeMb, floorMb, strongHardware, swapRoom = true)

internal fun MemoryGovernor.roomBesideSwap(
    availAfterMb: Long, floorMb: Long, strongHardware: Boolean, swapFreeMb: Long = Long.MAX_VALUE,
): Boolean = roomBesideSwap(availAfterMb, floorMb, strongHardware, swapFreeMb, swapRoom = true)

internal fun MemoryGovernor.decodeVerdict(
    mem: MemoryGovernor.Meminfo, chargeMb: Long, funded: Boolean, lowRam: Boolean, strongHardware: Boolean = false,
): GovernorVerdict = decodeVerdict(mem, chargeMb, funded, lowRam, strongHardware, swapRoom = true)

internal fun MemoryGovernor.swapVerdict(
    mem: MemoryGovernor.Meminfo, funded: Boolean, lowRam: Boolean = true, strongHardware: Boolean = false, chargeMb: Long = 0L,
): GovernorVerdict? = swapVerdict(mem, funded, lowRam, strongHardware, chargeMb, swapRoom = true)
