package io.github.veelume.postroad.advancement

import io.github.veelume.postroad.Postroad
import net.minecraft.server.level.ServerPlayer

/**
 * Milestones the mod grants itself. The advancement JSONs (`data/postroad/advancement/`) use
 * the `impossible` trigger, so nothing but this code awards them; FTB Quests uses them as
 * advancement tasks.
 */
object PostroadAdvancements {
    const val ROOT = "root"
    const val DEPOT_USED = "depot_used"
    const val HOME_SET = "home_set"
    const val POSTAL_NETWORK = "postal_network"
    const val PARCEL_SENT = "parcel_sent"
    const val MAILBOX_PLACED = "mailbox_placed"
    const val ROAD_WALKED = "road_walked"
    const val PATH_CHARTED = "path_charted"
    const val SIGN_LINKED = "sign_linked"
    const val FIRST_JOURNEY = "first_journey"
    const val LONG_JOURNEY = "long_journey"

    private const val CRITERION = "impossible"

    fun award(player: ServerPlayer, name: String) {
        if (name != ROOT) award(player, ROOT)
        val holder = player.server.advancements.get(Postroad.id(name)) ?: run {
            Postroad.LOGGER.warn("Advancement postroad:{} is missing", name)
            return
        }
        val progress = player.advancements.getOrStartProgress(holder)
        if (!progress.isDone) player.advancements.award(holder, CRITERION)
    }
}
