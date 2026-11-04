package dev.plex.ecc.nush;

import com.fastasyncworldedit.core.function.mask.BlockMaskBuilder;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.Filter;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;

import java.util.Set;
import java.util.UUID;

// Reads keep the inherited delegation, so the player sees the real world. Writes report success and change nothing.
// AbstractDelegateExtent sends every bulk write straight to the wrapped extent, so each one is overridden here.
// Java forbids Extent.super.setBlocks(...) while a superclass overrides that method, so the bulk overrides repeat
// what the Extent defaults count: one change per position a real edit would have written through this extent.
final class ShadowExtent extends AbstractDelegateExtent
{
    ShadowExtent(Extent extent)
    {
        super(extent);
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException
    {
        return true;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException
    {
        return true;
    }

    @Override
    public boolean tile(int x, int y, int z, FaweCompoundTag tag) throws WorldEditException
    {
        return true;
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome)
    {
        return true;
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome)
    {
        return true;
    }

    @Override
    public Entity createEntity(Location location, BaseEntity entity)
    {
        return null;
    }

    @Override
    public Entity createEntity(Location location, BaseEntity entity, UUID uuid)
    {
        return null;
    }

    @Override
    public void removeEntity(int x, int y, int z, UUID uuid)
    {
    }

    @Override
    public void setBlockLight(int x, int y, int z, int value)
    {
    }

    @Override
    public void setSkyLight(int x, int y, int z, int value)
    {
    }

    @Override
    public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block) throws MaxChangedBlocksException
    {
        return count(region);
    }

    @Override
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException
    {
        return count(region);
    }

    @Override
    public int setBlocks(Set<BlockVector3> positions, Pattern pattern)
    {
        return positions.size();
    }

    @Override
    public <B extends BlockStateHolder<B>> int replaceBlocks(Region region, Set<BaseBlock> filter, B replacement)
            throws MaxChangedBlocksException
    {
        return replaceBlocks(region, filter, (Pattern) replacement);
    }

    @Override
    public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern pattern) throws MaxChangedBlocksException
    {
        Mask mask = filter == null
                ? new ExistingBlockMask(this)
                : new BlockMaskBuilder().addBlocks(filter).build(this);
        return replaceBlocks(region, mask, pattern);
    }

    @Override
    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException
    {
        int changes = 0;
        for (BlockVector3 position : region)
        {
            // The mask reads through this extent, so it tests the real world and counts what the edit would change.
            if (mask.test(position))
            {
                changes++;
            }
        }
        return changes;
    }

    @Override
    public <T extends Filter> T apply(Region region, T filter, boolean full)
    {
        return filter;
    }

    @Override
    public <T extends Filter> T apply(Iterable<BlockVector3> positions, T filter)
    {
        return filter;
    }

    private static int count(Region region)
    {
        int changes = 0;
        for (BlockVector3 position : region)
        {
            changes++;
        }
        return changes;
    }
}
