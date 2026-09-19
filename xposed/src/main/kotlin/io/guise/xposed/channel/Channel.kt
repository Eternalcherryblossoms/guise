package io.guise.xposed.channel

import io.guise.xposed.HookContext

/**
 * One observable surface.
 *
 * Channels are the unit of coverage. Guise's equivalent was a flat list of
 * `if (json.has(...))` checks inside a single 170-line method, which made it impossible to
 * reason about which surfaces were covered -- and it covered roughly nine of them. Making
 * each surface an object with an [id] turns "what does this module actually spoof?" into an
 * enumerable list.
 */
interface Channel {
    val id: String

    /** Switches this channel on for one target process. Returns the number of hooks installed. */
    fun install(ctx: HookContext): Int
}
