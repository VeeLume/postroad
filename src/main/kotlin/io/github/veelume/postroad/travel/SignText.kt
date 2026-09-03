package io.github.veelume.postroad.travel

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.RoadNode
import net.minecraft.core.BlockPos
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText

/**
 * Writing onto signs. Vanilla and hanging signs through their API; Supplementaries way signs
 * through their tile's own `pointToward` (reflection, no compile dependency) plus an NBT
 * round trip for the text. Everything here degrades to "leave the sign alone" if a shape
 * is not what we expect.
 */
object SignWriter {

    /** Two-line town label on a vanilla/hanging sign, waxed so it stays. */
    fun labelTownSign(level: ServerLevel, entity: SignBlockEntity, name: String) {
        val words = name.split(' ')
        val line1: String
        val line2: String
        if (words.size >= 2 && name.length > 10) {
            val mid = words.size / 2
            line1 = words.take(mid).joinToString(" ")
            line2 = words.drop(mid).joinToString(" ")
        } else {
            line1 = name
            line2 = ""
        }
        val text = SignText().setMessage(0, Component.literal(line1)).setMessage(1, Component.literal(line2))
        entity.setText(text, true)
        entity.setText(text, false)
        entity.setWaxed(true)
        entity.setChanged()
        level.sendBlockUpdated(entity.blockPos, entity.blockState, entity.blockState, 3)
    }

    /**
     * Points a way sign's two arms along the path at [node]: the first active-able arm toward the
     * next node in the +index direction, the second toward the −index direction, each labelled
     * "To: <name>". Arms with nothing to point at are left as they were. Returns how many arms were set.
     */
    fun pointWaySign(level: ServerLevel, entity: BlockEntity, network: Network, node: RoadNode): Int {
        val path = network.paths[node.pathId] ?: return 0
        val onPath = network.nodes.values.filter { it.pathId == node.pathId && it.id != node.id }
        val forward = onPath.filter { it.pointIndex > node.pointIndex }.minByOrNull { it.pointIndex }
        val backward = onPath.filter { it.pointIndex < node.pointIndex }.maxByOrNull { it.pointIndex }
        val targets = listOfNotNull(forward, backward)
        if (targets.isEmpty()) return 0

        val arms = arms(entity) ?: return 0
        var set = 0
        val labels = ArrayList<String?>()
        for ((i, arm) in arms.withIndex()) {
            val target = targets.getOrNull(i)
            if (target == null) {
                labels.add(null)
                continue
            }
            try {
                val pointToward = arm.javaClass.getMethod("pointToward", BlockPos::class.java, BlockPos::class.java)
                val setActive = arm.javaClass.getMethod("setActive", java.lang.Boolean.TYPE)
                setActive.invoke(arm, true)
                pointToward.invoke(arm, node.pos, path.points[target.pointIndex])
                labels.add(Component.translatable("sign.postroad.to", target.name).string)
                set++
            } catch (e: Exception) {
                Postroad.LOGGER.warn("Could not point way-sign arm: {}", e.toString())
                labels.add(null)
            }
        }
        if (set == 0) return 0

        // Text goes in through the tile's own NBT: SignUp/SignDown.TextHolder.message[0].
        try {
            val registries = level.registryAccess()
            val tag = entity.saveWithoutMetadata(registries)
            for ((i, key) in listOf("SignUp", "SignDown").withIndex()) {
                val label = labels.getOrNull(i) ?: continue
                val arm = tag.getCompound(key)
                val holder = arm.getCompound("TextHolder")
                val messages = ListTag()
                messages.add(StringTag.valueOf(label))
                holder.put("message", messages)
                arm.put("TextHolder", holder)
                tag.put(key, arm)
            }
            entity.loadWithComponents(tag, registries)
            entity.setChanged()
            level.sendBlockUpdated(entity.blockPos, entity.blockState, entity.blockState, 3)
        } catch (e: Exception) {
            Postroad.LOGGER.warn("Could not write way-sign text: {}", e.toString())
        }
        return set
    }

    /** The way sign's arm objects, SignUp first. */
    private fun arms(entity: BlockEntity): List<Any>? {
        val fields = entity.javaClass.declaredFields.filter { it.type.simpleName == "Sign" }
        if (fields.size < 2) return null
        return try {
            fields.map { it.isAccessible = true; it.get(entity) }.filterNotNull().take(2)
        } catch (e: Exception) {
            null
        }
    }
}
