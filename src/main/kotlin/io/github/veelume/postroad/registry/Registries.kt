package io.github.veelume.postroad.registry

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.depot.DepotBlock
import io.github.veelume.postroad.depot.DepotBlockEntity
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.loot.LootedModifier
import io.github.veelume.postroad.mailbox.MailboxBlock
import io.github.veelume.postroad.mailbox.MailboxBlockEntity
import io.github.veelume.postroad.roads.ChartingMapItem
import io.github.veelume.postroad.salvage.SalvageRate
import io.github.veelume.postroad.salvage.SatchelItem
import io.github.veelume.postroad.salvage.SatchelMenu
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
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

    /** A player's home delivery point. Earned (quest reward), never crafted. */
    val MAILBOX: DeferredBlock<MailboxBlock> = REGISTER.registerBlock(
        "mailbox",
        ::MailboxBlock,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(2.5f).sound(SoundType.WOOD),
    )
}

object PostroadItems {
    val REGISTER: DeferredRegister.Items = DeferredRegister.createItems(Postroad.MOD_ID)

    /** The only money item. Sourced from loot, never crafted, never withdrawn. */
    val COIN: DeferredItem<Item> = REGISTER.registerSimpleItem("coin", Item.Properties())

    val DEPOT: DeferredItem<BlockItem> = REGISTER.registerSimpleBlockItem(PostroadBlocks.DEPOT)

    val MAILBOX: DeferredItem<BlockItem> = REGISTER.registerSimpleBlockItem(PostroadBlocks.MAILBOX)

    /** Charts roads into paths. Craftable: paper, string, feather, ink sac. */
    val CHARTING_MAP: DeferredItem<ChartingMapItem> = REGISTER.registerItem("charting_map", ::ChartingMapItem, Item.Properties().stacksTo(1))

    /** Sells looted gear and treasure on the spot, straight into the wallet. Craftable: leather, string, paper. */
    val SATCHEL: DeferredItem<SatchelItem> = REGISTER.registerItem("satchel", ::SatchelItem, Item.Properties().stacksTo(1))

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

    val MAILBOX: DeferredHolder<BlockEntityType<*>, BlockEntityType<MailboxBlockEntity>> = REGISTER.register(
        "mailbox",
        Supplier { BlockEntityType.Builder.of(::MailboxBlockEntity, PostroadBlocks.MAILBOX.get()).build(null) },
    )
}

object PostroadComponents {
    val REGISTER: DeferredRegister.DataComponents =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Postroad.MOD_ID)

    /** The old fresh-loot stamp: only read, to migrate it ([FreshLoot]). */
    val FRESH_LOOT: DeferredHolder<DataComponentType<*>, DataComponentType<FreshLoot>> =
        REGISTER.registerComponentType("fresh_loot") { builder ->
            builder.persistent(FreshLoot.CODEC).networkSynchronized(FreshLoot.STREAM_CODEC)
        }

    /** [io.github.veelume.postroad.loot.Looted]: unstackable container loot. */
    val LOOTED: DeferredHolder<DataComponentType<*>, DataComponentType<net.minecraft.util.Unit>> =
        REGISTER.registerComponentType("looted") { builder ->
            builder.persistent(Codec.unit(net.minecraft.util.Unit.INSTANCE))
                .networkSynchronized(net.minecraft.network.codec.StreamCodec.unit(net.minecraft.util.Unit.INSTANCE))
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
                    output.accept(PostroadItems.MAILBOX.get())
                    output.accept(PostroadItems.CHARTING_MAP.get())
                    output.accept(PostroadItems.SATCHEL.get())
                }
                .build()
        },
    )
}

object PostroadDataMaps {
    /** What the satchel pays per unit of an item ([io.github.veelume.postroad.salvage.Salvage]). Synced for tooltips. Data: `data/<ns>/data_maps/item/salvage.json`. */
    val SALVAGE: DataMapType<Item, SalvageRate> =
        DataMapType.builder(Postroad.id("salvage"), Registries.ITEM, SalvageRate.CODEC)
            .synced(SalvageRate.CODEC, false)
            .build()

    /** Coins for a looted piece of gear by its repair material. Synced. Data: `data/<ns>/data_maps/item/salvage_material.json`. */
    val SALVAGE_MATERIAL: DataMapType<Item, Int> =
        DataMapType.builder(Postroad.id("salvage_material"), Registries.ITEM, Codec.intRange(0, Int.MAX_VALUE))
            .synced(Codec.intRange(0, Int.MAX_VALUE), false)
            .build()

    fun register(event: RegisterDataMapTypesEvent) {
        event.register(SALVAGE)
        event.register(SALVAGE_MATERIAL)
    }
}

object PostroadLootModifiers {
    val REGISTER: DeferredRegister<MapCodec<out IGlobalLootModifier>> =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Postroad.MOD_ID)

    val LOOTED: DeferredHolder<MapCodec<out IGlobalLootModifier>, MapCodec<LootedModifier>> =
        REGISTER.register("looted", Supplier { LootedModifier.CODEC })
}

object PostroadMenus {
    val REGISTER: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, Postroad.MOD_ID)

    val DEPOT: DeferredHolder<MenuType<*>, MenuType<DepotMenu>> = REGISTER.register(
        "depot",
        Supplier { IMenuTypeExtension.create { id, inventory, buf -> DepotMenu.client(id, inventory, buf) } },
    )

    val SATCHEL: DeferredHolder<MenuType<*>, MenuType<SatchelMenu>> = REGISTER.register(
        "satchel",
        Supplier { MenuType({ id, inventory -> SatchelMenu(id, inventory) }, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS) },
    )
}

object PostroadSounds {
    val REGISTER: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Postroad.MOD_ID)

    /** The chime a mailbox owner hears when mail lands in it. */
    val MAIL_ARRIVED: DeferredHolder<SoundEvent, SoundEvent> =
        REGISTER.register("mail_arrived", Supplier { SoundEvent.createVariableRangeEvent(Postroad.id("mail_arrived")) })
}
