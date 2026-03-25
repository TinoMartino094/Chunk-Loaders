package com.tino.chunkloader.mixin;

import com.tino.chunkloader.ChunkAnchorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.class)
public class LodestoneBreakMixin {

    @Inject(method = "affectNeighborsAfterRemoval", at = @At("HEAD"))
    private void onAffectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston,
            CallbackInfo ci) {
        // If the block being removed is a lodestone
        if (state.is(Blocks.LODESTONE)) {
            // Unregister the chunk anchor if it exists
            ChunkAnchorManager manager = ChunkAnchorManager.get(level);
            manager.removeAnchor(level, pos);
            // Note: removeAnchor handles checking if the anchor actually exists,
            // and checking if there are other anchors in the chunk before unforcing.
        }
    }
}
