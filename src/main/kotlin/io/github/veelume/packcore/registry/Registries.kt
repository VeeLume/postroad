package io.github.veelume.packcore.registry

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import io.github.veelume.packcore.Packcore
import io.github.veelume.packcore.depot.DepotBlock
import io.github.veelume.packcore.depot.DepotBlockEntity
import io.github.veelume.packcore.loot.FreshLoot
import io.github.veelume.packcore.loot.FreshLootModifier
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import net.neoforged.neoforge.registries.datamaps.DataMapType
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent
import java.util.function.Supplier

object PackcoreBlocks {
    val REGISTER: DeferredRegister.Blocks = DeferredRegister.createBlocks(Packcore.MOD_ID)

    val DEPOT: DeferredBlock<DepotBlock> = REGISTER.registerBlock(
        "depot",
        ::DepotBlock,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD),
    )
}

object PackcoreItems {
    val REGISTER: DeferredRegister.Items = DeferredRegister.createItems(Packcore.MOD_ID)

    /** The only money item. Sourced from loot, never crafted, never withdrawn. */
    val COIN: DeferredItem<Item> = REGISTER.registerSimpleItem("coin", Item.Properties())

    val DEPOT: DeferredItem<BlockItem> = REGISTER.registerSimpleBlockItem(PackcoreBlocks.DEPOT)
}

object PackcoreBlockEntities {
    val REGISTER: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Packcore.MOD_ID)

    val DEPOT: DeferredHolder<BlockEntityType<*>, BlockEntityType<DepotBlockEntity>> = REGISTER.register(
        "depot",
        Supplier { BlockEntityType.Builder.of(::DepotBlockEntity, PackcoreBlocks.DEPOT.get()).build(null) },
    )
}

object PackcoreComponents {
    val REGISTER: DeferredRegister.DataComponents =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Packcore.MOD_ID)

    val FRESH_LOOT: DeferredHolder<DataComponentType<*>, DataComponentType<FreshLoot>> =
        REGISTER.registerComponentType("fresh_loot") { builder ->
            builder.persistent(FreshLoot.CODEC).networkSynchronized(FreshLoot.STREAM_CODEC)
        }
}

object PackcoreCreativeTabs {
    val REGISTER: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Packcore.MOD_ID)

    val MAIN: DeferredHolder<CreativeModeTab, CreativeModeTab> = REGISTER.register(
        "main",
        Supplier {
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.packcore"))
                .icon { ItemStack(PackcoreItems.COIN.get()) }
                .displayItems { _, output ->
                    output.accept(PackcoreItems.COIN.get())
                    output.accept(PackcoreItems.DEPOT.get())
                }
                .build()
        },
    )
}

object PackcoreDataMaps {
    /** Coins the depot pays per unit of a fresh-loot item. Data: `data/<ns>/data_maps/item/buyback.json`. */
    val BUYBACK: DataMapType<Item, Int> =
        DataMapType.builder(Packcore.id("buyback"), Registries.ITEM, Codec.intRange(0, Int.MAX_VALUE)).build()

    fun register(event: RegisterDataMapTypesEvent) {
        event.register(BUYBACK)
    }
}

object PackcoreLootModifiers {
    val REGISTER: DeferredRegister<MapCodec<out IGlobalLootModifier>> =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Packcore.MOD_ID)

    val FRESH_LOOT: DeferredHolder<MapCodec<out IGlobalLootModifier>, MapCodec<FreshLootModifier>> =
        REGISTER.register("fresh_loot", Supplier { FreshLootModifier.CODEC })
}
