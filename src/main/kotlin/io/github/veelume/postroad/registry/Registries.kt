package io.github.veelume.postroad.registry

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.depot.DepotBlock
import io.github.veelume.postroad.depot.DepotBlockEntity
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.loot.FreshLootModifier
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import io.github.veelume.postroad.menu.DepotMenu
import net.minecraft.world.inventory.MenuType
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import net.neoforged.neoforge.registries.datamaps.DataMapType
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent
import java.util.function.Supplier

object PostroadBlocks {
    val REGISTER: DeferredRegister.Blocks = DeferredRegister.createBlocks(Postroad.MOD_ID)

    val DEPOT: DeferredBlock<DepotBlock> = REGISTER.registerBlock(
        "depot",
        ::DepotBlock,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD),
    )
}

object PostroadItems {
    val REGISTER: DeferredRegister.Items = DeferredRegister.createItems(Postroad.MOD_ID)

    /** The only money item. Sourced from loot, never crafted, never withdrawn. */
    val COIN: DeferredItem<Item> = REGISTER.registerSimpleItem("coin", Item.Properties())

    val DEPOT: DeferredItem<BlockItem> = REGISTER.registerSimpleBlockItem(PostroadBlocks.DEPOT)

    /** Unlocks the postal network for the whole group when used on a depot. Quest reward, no recipe. */
    val POSTAL_CHARTER: DeferredItem<Item> =
        REGISTER.registerSimpleItem("postal_charter", Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
}

object PostroadBlockEntities {
    val REGISTER: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Postroad.MOD_ID)

    val DEPOT: DeferredHolder<BlockEntityType<*>, BlockEntityType<DepotBlockEntity>> = REGISTER.register(
        "depot",
        Supplier { BlockEntityType.Builder.of(::DepotBlockEntity, PostroadBlocks.DEPOT.get()).build(null) },
    )
}

object PostroadComponents {
    val REGISTER: DeferredRegister.DataComponents =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Postroad.MOD_ID)

    val FRESH_LOOT: DeferredHolder<DataComponentType<*>, DataComponentType<FreshLoot>> =
        REGISTER.registerComponentType("fresh_loot") { builder ->
            builder.persistent(FreshLoot.CODEC).networkSynchronized(FreshLoot.STREAM_CODEC)
        }
}

object PostroadCreativeTabs {
    val REGISTER: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Postroad.MOD_ID)

    val MAIN: DeferredHolder<CreativeModeTab, CreativeModeTab> = REGISTER.register(
        "main",
        Supplier {
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.postroad"))
                .icon { ItemStack(PostroadItems.COIN.get()) }
                .displayItems { _, output ->
                    output.accept(PostroadItems.COIN.get())
                    output.accept(PostroadItems.DEPOT.get())
                    output.accept(PostroadItems.POSTAL_CHARTER.get())
                }
                .build()
        },
    )
}

object PostroadDataMaps {
    /** Coins the depot pays per unit of a fresh-loot item. Synced to clients for tooltips. Data: `data/<ns>/data_maps/item/buyback.json`. */
    val BUYBACK: DataMapType<Item, Int> =
        DataMapType.builder(Postroad.id("buyback"), Registries.ITEM, Codec.intRange(0, Int.MAX_VALUE))
            .synced(Codec.intRange(0, Int.MAX_VALUE), false)
            .build()

    fun register(event: RegisterDataMapTypesEvent) {
        event.register(BUYBACK)
    }
}

object PostroadLootModifiers {
    val REGISTER: DeferredRegister<MapCodec<out IGlobalLootModifier>> =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Postroad.MOD_ID)

    val FRESH_LOOT: DeferredHolder<MapCodec<out IGlobalLootModifier>, MapCodec<FreshLootModifier>> =
        REGISTER.register("fresh_loot", Supplier { FreshLootModifier.CODEC })
}

object PostroadMenus {
    val REGISTER: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, Postroad.MOD_ID)

    val DEPOT: DeferredHolder<MenuType<*>, MenuType<DepotMenu>> = REGISTER.register(
        "depot",
        Supplier { IMenuTypeExtension.create { id, inventory, buf -> DepotMenu.client(id, inventory, buf) } },
    )
}
