package com.tino.chunkloader.mixin;

import com.tino.chunkloader.ChunkAnchorManager;
import com.tino.chunkloader.LodestoneChunkLoaders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CompassItem.class)
public class CompassItemMixin {

    /**
     * After vanilla lodestone linking, set initial lore with anchor time.
     */
    @Inject(method = "useOn", at = @At("RETURN"))
    private void onUseOnLodestone(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        if (level.isClientSide())
            return;

        BlockPos pos = context.getClickedPos();
        if (!level.getBlockState(pos).is(Blocks.LODESTONE))
            return;

        ChunkAnchorManager manager = LodestoneChunkLoaders.getAnchorManager();
        if (manager == null)
            return;

        ServerLevel serverLevel = (ServerLevel) level;
        long timeMs = manager.getStoredTimeMs(serverLevel, pos);

        Player player = context.getPlayer();
        if (player == null)
            return;

        String ownerName = manager.getOwnerName(serverLevel, pos);
        ItemLore lore = (timeMs > 0) ? buildTimeLore(timeMs, ownerName) : buildNotChargedLore();

        // Case 1: The held item was modified in place (stack count was 1, not creative)
        ItemStack heldItem = context.getItemInHand();
        if (heldItem.has(DataComponents.LODESTONE_TRACKER)) {
            heldItem.set(DataComponents.LORE, lore);
            return;
        }

        // Case 2: A new compass was created and added to inventory (creative or count >
        // 1)
        GlobalPos target = GlobalPos.of(level.dimension(), pos);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.COMPASS)) {
                LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
                if (tracker != null && tracker.target().isPresent() && tracker.target().get().equals(target)) {
                    stack.set(DataComponents.LORE, lore);
                    break;
                }
            }
        }
    }

    /**
     * Every tick, update the lore on lodestone compasses with live anchor time.
     * Only updates every ~2 seconds (40 ticks) to reduce overhead.
     */
    @Inject(method = "inventoryTick", at = @At("RETURN"))
    private void onInventoryTick(ItemStack itemStack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot,
            CallbackInfo ci) {
        // Only update every 40 ticks (~2 seconds) to reduce overhead
        if (level.getServer().getTickCount() % 40 != 0)
            return;

        LodestoneTracker tracker = itemStack.get(DataComponents.LODESTONE_TRACKER);
        if (tracker == null)
            return;

        if (tracker.target().isEmpty()) {
            itemStack.remove(DataComponents.LORE);
            return;
        }

        ChunkAnchorManager manager = LodestoneChunkLoaders.getAnchorManager();
        if (manager == null)
            return;

        GlobalPos globalPos = tracker.target().get();
        // Only update if the compass is tracking a position in a loaded dimension
        ServerLevel targetLevel = level.getServer().getLevel(globalPos.dimension());
        if (targetLevel == null)
            return;

        long timeMs = manager.getStoredTimeMs(targetLevel, globalPos.pos());
        if (timeMs > 0) {
            String ownerName = manager.getOwnerName(targetLevel, globalPos.pos());
            itemStack.set(DataComponents.LORE, buildTimeLore(timeMs, ownerName));
        } else {
            itemStack.set(DataComponents.LORE, buildNotChargedLore());
        }
    }

    private static ItemLore buildTimeLore(long timeMs, String ownerName) {
        String timeStr = ChunkAnchorManager.formatTime(timeMs);
        return new ItemLore(List.of(
                Component.literal("§7Chunk Anchor (§a" + ownerName + "§7): §e" + timeStr + " §7remaining")));
    }

    private static ItemLore buildNotChargedLore() {
        return new ItemLore(List.of(
                Component.literal("§7Chunk Anchor: §cNot charged")));
    }
}
