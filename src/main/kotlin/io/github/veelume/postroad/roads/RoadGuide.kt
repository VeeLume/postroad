package io.github.veelume.postroad.roads

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * What the charting guide explains, from the server's own data: the road rules (so the client can
 * classify every block it knows the same way the server does) and the numbers that hang on a tier.
 * Percentages are whole numbers, indexed by [Tier] ordinal.
 */
data class RoadGuide(
    val rules: RoadRuleSet,
    val buffPercent: List<Int>,
    val farePercent: List<Int>,
    val surveyBlocksPerCoin: Int,
    val surveyTownBonus: Long,
) {
    companion object {
        val EMPTY = RoadGuide(RoadRuleSet.FALLBACK, listOf(0, 0, 0), listOf(100, 100, 100), 0, 0)

        fun current(): RoadGuide = RoadGuide(
            RoadRules.current,
            listOf(PostroadConfig.buffDirt, PostroadConfig.buffGravel, PostroadConfig.buffPaved).map { Math.round(it * 100).toInt() },
            listOf(PostroadConfig.tierFactorDirt, PostroadConfig.tierFactorGravel, PostroadConfig.tierFactorPaved).map { Math.round(it * 100).toInt() },
            PostroadConfig.surveyBlocksPerCoin,
            PostroadConfig.surveyTownBonus,
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadGuide> = StreamCodec.of(
            { buf, g ->
                val r = g.rules
                buf.writeDouble(r.minRoadShare); buf.writeVarInt(r.sampleSpacingMin); buf.writeVarInt(r.sampleSpacingMax); buf.writeVarInt(r.sampleRadius)
                for (tier in Tier.entries) {
                    buf.writeCollection(r.exact[tier]?.toList() ?: emptyList()) { b, v -> b.writeUtf(v) }
                    buf.writeCollection(r.keywords[tier] ?: emptyList()) { b, v -> b.writeUtf(v) }
                }
                buf.writeCollection(g.buffPercent) { b, v -> b.writeVarInt(v) }
                buf.writeCollection(g.farePercent) { b, v -> b.writeVarInt(v) }
                buf.writeVarInt(g.surveyBlocksPerCoin); buf.writeVarLong(g.surveyTownBonus)
            },
            { buf ->
                val share = buf.readDouble(); val min = buf.readVarInt(); val max = buf.readVarInt(); val radius = buf.readVarInt()
                val exact = HashMap<Tier, Set<String>>(); val keywords = HashMap<Tier, List<String>>()
                for (tier in Tier.entries) {
                    exact[tier] = buf.readList { it.readUtf() }.toSet()
                    keywords[tier] = buf.readList { it.readUtf() }
                }
                RoadGuide(RoadRuleSet(share, min, max, radius, exact, keywords),
                    buf.readList { it.readVarInt() }, buf.readList { it.readVarInt() }, buf.readVarInt(), buf.readVarLong())
            },
        )
    }
}

/** Server → client: open the charting guide. */
data class RoadGuidePayload(val guide: RoadGuide) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RoadGuidePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RoadGuidePayload> = CustomPacketPayload.Type(Postroad.id("road_guide"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadGuidePayload> = RoadGuide.STREAM_CODEC.map(::RoadGuidePayload, RoadGuidePayload::guide)
    }
}

/** Client-side holder; no client imports. */
object RoadGuideClient {
    @Volatile
    var guide: RoadGuide = RoadGuide.EMPTY
    var screenOpener: Runnable? = null
}
