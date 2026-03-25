package com.tino.chunkloader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LodestoneChunkLoaders implements ModInitializer {
	public static final String MOD_ID = "lodestone-chunk-loaders";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Configurable: how many hours of offline time each Recovery Compass adds */
	public static long CONFIG_OFFLINE_TIME_HOURS = 12;

	private static ChunkAnchorManager anchorManager;

	@Override
	public void onInitialize() {
		LOGGER.info("Lodestone Chunk Loaders initializing...");

		// --- Server Started: Load data and reactivate tickets ---
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ServerLevel overworld = server.overworld();
			anchorManager = overworld.getChunkSource().getDataStorage().computeIfAbsent(ChunkAnchorManager.TYPE);
			anchorManager.reactivateAllTickets(server);
			LOGGER.info("Chunk Anchor Manager loaded and tickets reactivated.");
		});

		// --- Server Tick: Drain offline time ---
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (anchorManager != null) {
				anchorManager.tick(server);
			}
		});

		// --- Use Block: Handle lodestone right-clicks ---
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide())
				return InteractionResult.PASS;
			if (hand != InteractionHand.MAIN_HAND)
				return InteractionResult.PASS;

			BlockPos pos = hitResult.getBlockPos();
			if (!world.getBlockState(pos).is(Blocks.LODESTONE))
				return InteractionResult.PASS;
			if (anchorManager == null)
				return InteractionResult.PASS;

			ServerLevel serverLevel = (ServerLevel) world;
			ServerPlayer serverPlayer = (ServerPlayer) player;
			ItemStack heldItem = player.getItemInHand(hand);

			// --- Recovery Compass: Charge the anchor ---
			if (heldItem.is(Items.RECOVERY_COMPASS)) {
				if (!player.hasInfiniteMaterials()) {
					heldItem.shrink(1);
				}

				anchorManager.chargeAnchor(serverLevel, pos, serverPlayer);

				world.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F);

				long timeMs = anchorManager.getStoredTimeMs(serverLevel, pos);
				serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
						Component.literal(
								"§aChunk Loader charged! §7Time: §e" + ChunkAnchorManager.formatTime(timeMs))));

				return InteractionResult.SUCCESS;
			}

			// --- Standard Compass: Let vanilla linking happen (CompassItemMixin handles
			// lore) ---
			if (heldItem.is(Items.COMPASS)) {
				return InteractionResult.PASS;
			}

			// --- Empty Hand: Show remaining time ---
			if (heldItem.isEmpty()) {
				long timeMs = anchorManager.getStoredTimeMs(serverLevel, pos);
				if (timeMs > 0) {
					String ownerName = anchorManager.getOwnerName(serverLevel, pos);
					serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
							Component.literal(
									"§7Chunk Loader (§a" + ownerName + "§7): §e"
											+ ChunkAnchorManager.formatTime(timeMs) + " §7remaining")));
				} else {
					serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
							Component.literal("§7Chunk Loader: §cNot charged")));
				}
				return InteractionResult.PASS;
			}

			return InteractionResult.PASS;
		});

		// --- Block Break: Revoke anchor on lodestone break ---
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world.isClientSide())
				return;
			if (!state.is(Blocks.LODESTONE))
				return;
			if (anchorManager == null)
				return;

			ServerLevel serverLevel = (ServerLevel) world;
			long timeMs = anchorManager.getStoredTimeMs(serverLevel, pos);
			if (timeMs > 0) {
				anchorManager.removeAnchor(serverLevel, pos);
				if (player instanceof ServerPlayer serverPlayer) {
					serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
							Component.literal("§cChunk Loader destroyed.")));
				}
			}
		});

		LOGGER.info("Lodestone Chunk Loaders initialized.");
	}

	/** Public accessor for the anchor manager (used by mixins) */
	public static ChunkAnchorManager getAnchorManager() {
		return anchorManager;
	}
}