package io.github.veelume.postroad.roads

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.advancement.PostroadAdvancements
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.neoforged.neoforge.event.tick.PlayerTickEvent

/**
 * Layer one of the road design: walking on any recognised road is faster, scaled by tier.
 * A transient movement-speed modifier, refreshed every 10 ticks for the player and, if they
 * ride, their mount. No ceremony, no recording — generated or built, if it reads as road it
 * counts.
 */
object RoadBuff {
    val MODIFIER_ID: ResourceLocation = Postroad.id("road_buff")
    private const val INTERVAL = 10

    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.tickCount % INTERVAL != 0) return
        update(player)
    }

    /** Applies or clears the buff for [player] and their vehicle based on the ground under them. */
    fun update(player: Player): Tier? {
        val carrier: LivingEntity = (player.vehicle as? LivingEntity) ?: player
        val tier = RoadClassifier.sample(player.level(), carrier.onPos, radius = 1)
        val amount = tier?.let { strength(it) } ?: 0.0
        apply(player, amount)
        val vehicle = player.vehicle as? LivingEntity
        if (vehicle != null) apply(vehicle, amount) else Unit
        if (tier != null && player is ServerPlayer) PostroadAdvancements.award(player, PostroadAdvancements.ROAD_WALKED)
        return tier
    }

    fun strength(tier: Tier): Double = when (tier) {
        Tier.DIRT -> PostroadConfig.buffDirt
        Tier.GRAVEL -> PostroadConfig.buffGravel
        Tier.PAVED -> PostroadConfig.buffPaved
    }

    private fun apply(entity: LivingEntity, amount: Double) {
        val attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        if (amount <= 0.0) {
            attribute.removeModifier(MODIFIER_ID)
            return
        }
        val existing = attribute.getModifier(MODIFIER_ID)
        if (existing != null && existing.amount == amount) return
        attribute.addOrUpdateTransientModifier(AttributeModifier(MODIFIER_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    fun currentStrength(entity: LivingEntity): Double =
        entity.getAttribute(Attributes.MOVEMENT_SPEED)?.getModifier(MODIFIER_ID)?.amount ?: 0.0
}
