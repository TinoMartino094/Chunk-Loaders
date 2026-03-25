package com.tino.chunkloader;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ChunkAnchorManager extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger("lodestone-chunk-loaders");

    // --- Anchor Entry ---
    public static class AnchorEntry {
        public static final Codec<AnchorEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("x").forGetter(e -> e.pos.getX()),
                Codec.INT.fieldOf("y").forGetter(e -> e.pos.getY()),
                Codec.INT.fieldOf("z").forGetter(e -> e.pos.getZ()),
                Codec.STRING.fieldOf("dimension").forGetter(e -> e.dimension),
                Codec.STRING.fieldOf("owner_uuid").forGetter(e -> e.ownerUuid.toString()),
                Codec.STRING.optionalFieldOf("owner_name", "Unknown").forGetter(e -> e.ownerName),
                Codec.LONG.fieldOf("stored_time_ms").forGetter(e -> e.storedTimeMs),
                Codec.LONG.fieldOf("last_drain_time").forGetter(e -> e.lastDrainTime))
                .apply(i, (x, y, z, dim, uuid, name, stored, lastDrain) -> new AnchorEntry(new BlockPos(x, y, z), dim,
                        UUID.fromString(uuid), name, stored, lastDrain)));

        public final BlockPos pos;
        public final String dimension;
        public final UUID ownerUuid;
        public String ownerName;
        public long storedTimeMs;
        public long lastDrainTime; // System.currentTimeMillis() of last drain check

        public AnchorEntry(BlockPos pos, String dimension, UUID ownerUuid, String ownerName, long storedTimeMs,
                long lastDrainTime) {
            this.pos = pos;
            this.dimension = dimension;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.storedTimeMs = storedTimeMs;
            this.lastDrainTime = lastDrainTime;
        }

        /** Returns a unique key for this anchor based on dimension + position */
        public String getKey() {
            return dimension + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    // --- Codec and SavedDataType ---
    public static final Codec<ChunkAnchorManager> CODEC = RecordCodecBuilder.create(i -> i.group(
            AnchorEntry.CODEC.listOf().optionalFieldOf("anchors", List.of())
                    .forGetter(m -> new ArrayList<>(m.anchors.values())))
            .apply(i, ChunkAnchorManager::fromList));

    public static final SavedDataType<ChunkAnchorManager> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace("lodestone_chunk_anchors"),
            ChunkAnchorManager::new,
            CODEC,
            DataFixTypes.SAVED_DATA_FORCED_CHUNKS);

    // --- State ---
    private final Map<String, AnchorEntry> anchors = new HashMap<>();
    private long lastTickTime = 0;

    public ChunkAnchorManager() {
    }

    private static ChunkAnchorManager fromList(List<AnchorEntry> entries) {
        ChunkAnchorManager manager = new ChunkAnchorManager();
        for (AnchorEntry entry : entries) {
            manager.anchors.put(entry.getKey(), entry);
        }
        return manager;
    }

    public static ChunkAnchorManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // --- Public API ---

    /**
     * Called when a player right-clicks a Lodestone with a Recovery Compass.
     * Adds time to the stored ledger and registers the chunk as force-loaded.
     */
    public void chargeAnchor(ServerLevel level, BlockPos pos, ServerPlayer player) {
        String dimension = level.dimension().identifier().toString();
        String key = dimension + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();

        AnchorEntry existing = anchors.get(key);
        long addMs = LodestoneChunkLoaders.CONFIG_OFFLINE_TIME_HOURS * 60L * 60L * 1000L;
        UUID ownerUuid = player.getUUID();
        String ownerName = player.getScoreboardName();

        if (existing != null) {
            existing.storedTimeMs += addMs;
            existing.lastDrainTime = System.currentTimeMillis();
        } else {
            AnchorEntry entry = new AnchorEntry(pos, dimension, ownerUuid, ownerName, addMs,
                    System.currentTimeMillis());
            anchors.put(key, entry);
        }

        // Force-load the chunk
        ChunkPos chunkPos = ChunkPos.containing(pos);
        level.getChunkSource().updateChunkForced(chunkPos, true);

        this.setDirty();
        LOGGER.info("Chunk anchor charged at {} in {} by {} | Total time: {}ms",
                pos.toShortString(), dimension, ownerUuid, anchors.get(key).storedTimeMs);
    }

    /**
     * Gets the stored time for the anchor at the given position, or -1 if none
     * exists.
     */
    public long getStoredTimeMs(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().identifier().toString();
        String key = dimension + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        AnchorEntry entry = anchors.get(key);
        return entry != null ? entry.storedTimeMs : -1;
    }

    /**
     * Gets the owner name for the anchor at the given position, or "Unknown" if
     * none exists.
     */
    public String getOwnerName(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().identifier().toString();
        String key = dimension + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        AnchorEntry entry = anchors.get(key);
        return entry != null ? entry.ownerName : "Unknown";
    }

    /**
     * Removes the anchor at the given position and revokes force-loading.
     */
    public void removeAnchor(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().identifier().toString();
        String key = dimension + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        AnchorEntry removed = anchors.remove(key);
        if (removed != null) {
            ChunkPos chunkPos = ChunkPos.containing(pos);
            // Only unforce the chunk if no other anchors remain in it
            if (!hasOtherAnchorInChunk(dimension, chunkPos)) {
                level.getChunkSource().updateChunkForced(chunkPos, false);
            }
            this.setDirty();
            LOGGER.info("Chunk anchor removed at {} in {}", pos.toShortString(), dimension);
        }
    }

    /**
     * Called every server tick. Drains time from anchors whose owners are offline.
     * Uses IRL time (System.currentTimeMillis) for accurate drain even if server
     * was off.
     */
    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        // Only process every ~60 seconds of game ticks (1200 ticks) to reduce overhead
        // But we use IRL timestamps for the actual drain calculation
        if (now - lastTickTime < 60_000L) {
            return;
        }
        lastTickTime = now;

        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, AnchorEntry> mapEntry : anchors.entrySet()) {
            AnchorEntry entry = mapEntry.getValue();

            // Check if owner is online
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerUuid);
            boolean isOnline = owner != null;

            if (!isOnline) {
                // Calculate how much IRL time has passed since last drain
                long elapsed = now - entry.lastDrainTime;
                entry.storedTimeMs -= elapsed;
                entry.lastDrainTime = now;

                if (entry.storedTimeMs <= 0) {
                    entry.storedTimeMs = 0;
                    toRemove.add(mapEntry.getKey());
                    LOGGER.info("Chunk anchor expired at {} in {}", entry.pos.toShortString(), entry.dimension);
                }
            } else {
                // Owner is online, update the drain time so it doesn't drain retroactively
                entry.lastDrainTime = now;
                // Dynamically update their name in case it changed since they last charged it
                String currentName = owner.getScoreboardName();
                if (!currentName.equals(entry.ownerName)) {
                    entry.ownerName = currentName;
                    this.setDirty();
                }
            }
        }

        // Remove expired anchors and revoke forced loading
        for (String key : toRemove) {
            AnchorEntry entry = anchors.remove(key);
            if (entry != null) {
                ServerLevel level = getLevelForDimension(server, entry.dimension);
                if (level != null) {
                    ChunkPos chunkPos = ChunkPos.containing(entry.pos);
                    // Only unforce if no other anchors remain in this chunk
                    if (!hasOtherAnchorInChunk(entry.dimension, chunkPos)) {
                        level.getChunkSource().updateChunkForced(chunkPos, false);
                    }
                }
            }
        }

        if (!toRemove.isEmpty()) {
            this.setDirty();
        }

        // Also mark dirty periodically to save drain progress
        this.setDirty();
    }

    /**
     * Re-registers all FORCED tickets on server start (after data is loaded).
     */
    public void reactivateAllTickets(MinecraftServer server) {
        for (AnchorEntry entry : anchors.values()) {
            // Recalculate drain from IRL time since last save
            long now = System.currentTimeMillis();
            if (entry.lastDrainTime > 0) {
                ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerUuid);
                if (owner == null) {
                    long elapsed = now - entry.lastDrainTime;
                    entry.storedTimeMs -= elapsed;
                    if (entry.storedTimeMs < 0)
                        entry.storedTimeMs = 0;
                }
                entry.lastDrainTime = now;
            }

            if (entry.storedTimeMs > 0) {
                ServerLevel level = getLevelForDimension(server, entry.dimension);
                if (level != null) {
                    ChunkPos chunkPos = ChunkPos.containing(entry.pos);
                    level.getChunkSource().updateChunkForced(chunkPos, true);
                    LOGGER.info("Reactivated chunk anchor at {} in {} with {}ms remaining",
                            entry.pos.toShortString(), entry.dimension, entry.storedTimeMs);
                }
            }
        }
        this.setDirty();
    }

    private static ServerLevel getLevelForDimension(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Checks if any active anchor (with time > 0) exists in the given chunk.
     * Called AFTER the anchor being removed has already been deleted from the map.
     */
    private boolean hasOtherAnchorInChunk(String dimension, ChunkPos chunkPos) {
        for (AnchorEntry entry : anchors.values()) {
            if (entry.dimension.equals(dimension) && entry.storedTimeMs > 0) {
                ChunkPos entryChunk = ChunkPos.containing(entry.pos);
                if (entryChunk.x() == chunkPos.x() && entryChunk.z() == chunkPos.z()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Formats milliseconds into a human-readable time string.
     */
    public static String formatTime(long ms) {
        if (ms <= 0)
            return "0s";
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0)
            sb.append(hours).append("h ");
        if (minutes > 0)
            sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty())
            sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
