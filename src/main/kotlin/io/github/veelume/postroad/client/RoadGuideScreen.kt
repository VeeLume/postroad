package io.github.veelume.postroad.client

import io.github.veelume.postroad.roads.RoadGuideClient
import io.github.veelume.postroad.roads.Tier
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The charting guide: what counts as road and as which tier, worked out on the client with the
 * server's rules over every block the game knows (so modded blocks show too), with what each tier
 * gives — speed, fares — and what charting pays. Blocks the rules name exactly come first.
 */
class RoadGuideScreen : Screen(Component.translatable("screen.postroad.guide.title")) {
    private val guide = RoadGuideClient.guide
    /** Per tier: the item of every block that classifies as it. */
    private val byTier: Map<Tier, List<ItemStack>> = run {
        val rules = guide.rules
        val out = Tier.entries.associateWith { ArrayList<Pair<Boolean, ItemStack>>() }
        for (block in BuiltInRegistries.BLOCK) {
            val tier = rules.classify(block.defaultBlockState()) ?: continue
            val item = block.asItem()
            if (item == Items.AIR) continue
            val id = BuiltInRegistries.BLOCK.getKey(block).toString()
            out[tier]!!.add((rules.exact[tier]?.contains(id) == true) to ItemStack(item))
        }
        out.mapValues { (_, list) ->
            list.sortedWith(compareBy<Pair<Boolean, ItemStack>> { !it.first }
                .thenBy { BuiltInRegistries.ITEM.getKey(it.second.item).namespace != "minecraft" }
                .thenBy { BuiltInRegistries.ITEM.getKey(it.second.item).toString() })
                .map { it.second }
        }
    }
    private var hovered: ItemStack? = null

    override fun init() {
        super.init()
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) { onClose() }.bounds(width / 2 - 50, height - 28, 100, 20).build())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        hovered = null
        val left = MARGIN
        val textW = width - MARGIN * 2
        var y = 10
        graphics.drawCenteredString(font, title, width / 2, y, 0xFFFFFF)
        y += 16
        val rules = guide.rules
        val intro = Component.translatable("screen.postroad.guide.rule", Math.round(rules.minRoadShare * 100), rules.sampleSpacingMin, rules.sampleSpacingMax, rules.sampleRadius)
        graphics.drawWordWrap(font, intro, left, y, textW, 0xC0C0C0)
        y += font.wordWrapHeight(intro, textW) + 8

        val perRow = ((width - MARGIN * 2) / 18).coerceAtLeast(1)
        // What is left for the icon rows, shared by the three tiers.
        val footer = 70
        val rowsLeft = ((height - footer - y - Tier.entries.size * 26) / 18).coerceAtLeast(Tier.entries.size)
        for (tier in Tier.entries) {
            val items = byTier[tier].orEmpty()
            val header = Component.translatable("screen.postroad.guide.tier", Component.translatable("tier.postroad.${tier.key}"),
                guide.buffPercent.getOrElse(tier.ordinal) { 0 }, guide.farePercent.getOrElse(tier.ordinal) { 100 } / 100.0)
            graphics.drawString(font, header, left, y, TIER_TEXT[tier.ordinal], true)
            y += 12
            val rows = minOf((items.size + perRow - 1) / perRow, if (tier == Tier.PAVED) rowsLeft - 2 else 2).coerceAtLeast(1)
            val shown = minOf(items.size, rows * perRow - if (items.size > rows * perRow) 1 else 0)
            for (i in 0 until shown) {
                val x = left + (i % perRow) * 18; val iy = y + (i / perRow) * 18
                graphics.renderItem(items[i], x, iy)
                if (mouseX in x until x + 16 && mouseY in iy until iy + 16) hovered = items[i]
            }
            if (shown < items.size) {
                val i = shown
                graphics.drawString(font, "+${items.size - shown}", left + (i % perRow) * 18 + 1, y + (i / perRow) * 18 + 4, 0xA0A0A0, false)
            }
            val keywords = rules.keywords[tier].orEmpty()
            y += rows * 18 + 2
            if (keywords.isNotEmpty()) {
                val text = Component.translatable("screen.postroad.guide.keywords", keywords.joinToString(", "))
                val lines = font.split(text, textW).take(2)
                for (line in lines) { graphics.drawString(font, line, left, y, 0x808080, false); y += 10 }
            }
            y += 4
        }
        val tail = Component.translatable("screen.postroad.guide.tiering").copy()
        if (guide.surveyBlocksPerCoin > 0) tail.append(" ").append(Component.translatable("screen.postroad.guide.pays", guide.surveyBlocksPerCoin, guide.surveyTownBonus))
        graphics.drawWordWrap(font, tail, left, height - footer + 4, textW, 0xC0C0C0)
        hovered?.let { graphics.renderTooltip(font, it, mouseX, mouseY) }
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val MARGIN = 16
        private val TIER_TEXT = intArrayOf(0xD0A070, 0xC0C8CC, 0xF0E8D0)
    }
}
