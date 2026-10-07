package com.ovigia;

import com.ovigia.entity.WatcherEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Heightmap;

public class OVigia implements ModInitializer {
	public static final String MOD_ID = "ovigia";

	/** Chance de spawn natural. Zumbi = 100. Sugestão: 10 = raro, 40 = comum, 80 = quase tão comum quanto um zumbi. */
	private static final int SPAWN_WEIGHT = 40;

	public static final EntityType<WatcherEntity> WATCHER = Registry.register(
			Registries.ENTITY_TYPE,
			new Identifier(MOD_ID, "watcher"),
			FabricEntityTypeBuilder.<WatcherEntity>createMob()
					.spawnGroup(SpawnGroup.MONSTER)
					.entityFactory(WatcherEntity::new)
					.spawnRestriction(SpawnRestriction.Location.ON_GROUND,
							Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
							(type, world, reason, pos, random) ->
									world.getDifficulty() != Difficulty.PEACEFUL
											&& MobEntity.canMobSpawn(type, world, reason, pos, random))
					.dimensions(EntityDimensions.fixed(0.6f, 2.0f))
					.build());

	public static final Item WATCHER_SPAWN_EGG = Registry.register(
			Registries.ITEM,
			new Identifier(MOD_ID, "watcher_spawn_egg"),
			new SpawnEggItem(WATCHER, 0x0A0A0A, 0xE8E8E8, new FabricItemSettings()));

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(WATCHER, WatcherEntity.createAttributes());
		VigiaChat.register();

		// Aparece no Overworld, sempre sozinho. Ajuste SPAWN_WEIGHT para mudar a frequência.
		BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), SpawnGroup.MONSTER, WATCHER, SPAWN_WEIGHT, 1, 1);

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(WATCHER_SPAWN_EGG));
	}
}
