package com.nullspace.block;

import com.nullspace.registry.ModBlockEntities;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class GeneratorBlockEntity extends BlockEntity {
	public static final int MAX_FUEL = 20 * 60 * 20; // 20 минут
	private static final int LAMP_RADIUS = 12;

	private int fuel;
	/** Лампы, которые генератор оживил, — чтобы погасить их обратно. */
	private final List<Long> revivedDead = new ArrayList<>();
	private final List<Long> revivedFlickering = new ArrayList<>();

	public GeneratorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.GENERATOR, pos, state);
	}

	public int secondsLeft() {
		return fuel / 20;
	}

	/** @return false, если бак полон. */
	public boolean addFuel(int amount) {
		if (fuel >= MAX_FUEL - 20) return false;
		fuel = Math.min(MAX_FUEL, fuel + amount);
		markDirty();
		return true;
	}

	public static void tick(World world, BlockPos pos, BlockState state, GeneratorBlockEntity be) {
		boolean lit = state.get(GeneratorBlock.LIT);
		if (be.fuel > 0) {
			be.fuel--;
			if (!lit) {
				world.setBlockState(pos, state.with(GeneratorBlock.LIT, true), Block.NOTIFY_ALL);
				world.playSound(null, pos, ModSounds.GENERATOR_START, SoundCategory.BLOCKS, 1.0f, 1.0f);
				be.reviveLamps(world);
			}
			if (world.getTime() % 40 == 0) {
				world.playSound(null, pos, ModSounds.GENERATOR_HUM, SoundCategory.BLOCKS, 0.7f, 1.0f);
			}
			if (be.fuel % 100 == 0) be.markDirty();
		} else if (lit) {
			world.setBlockState(pos, state.with(GeneratorBlock.LIT, false), Block.NOTIFY_ALL);
			world.playSound(null, pos, ModSounds.GENERATOR_STOP, SoundCategory.BLOCKS, 1.0f, 1.0f);
			be.revertLamps(world);
			be.markDirty();
		}
	}

	private void reviveLamps(World world) {
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -LAMP_RADIUS; dx <= LAMP_RADIUS; dx++) {
			for (int dz = -LAMP_RADIUS; dz <= LAMP_RADIUS; dz++) {
				for (int dy = 1; dy <= 8; dy++) {
					p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
					BlockState s = world.getBlockState(p);
					if (s.isOf(ModBlocks.CEILING_LIGHT_OFF)) {
						revivedDead.add(p.asLong());
						world.setBlockState(p, ModBlocks.CEILING_LIGHT.getDefaultState(), Block.NOTIFY_ALL);
					} else if (s.isOf(ModBlocks.CEILING_LIGHT_FLICKERING)) {
						revivedFlickering.add(p.asLong());
						world.setBlockState(p, ModBlocks.CEILING_LIGHT.getDefaultState(), Block.NOTIFY_ALL);
					}
				}
			}
		}
		markDirty();
	}

	public void revertLamps(World world) {
		for (long l : revivedDead) {
			BlockPos p = BlockPos.fromLong(l);
			if (world.getBlockState(p).isOf(ModBlocks.CEILING_LIGHT)) {
				world.setBlockState(p, ModBlocks.CEILING_LIGHT_OFF.getDefaultState(), Block.NOTIFY_ALL);
			}
		}
		for (long l : revivedFlickering) {
			BlockPos p = BlockPos.fromLong(l);
			if (world.getBlockState(p).isOf(ModBlocks.CEILING_LIGHT)) {
				world.setBlockState(p, ModBlocks.CEILING_LIGHT_FLICKERING.getDefaultState(), Block.NOTIFY_ALL);
			}
		}
		revivedDead.clear();
		revivedFlickering.clear();
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putInt("fuel", fuel);
		nbt.putLongArray("revived_dead", revivedDead.stream().mapToLong(Long::longValue).toArray());
		nbt.putLongArray("revived_flickering", revivedFlickering.stream().mapToLong(Long::longValue).toArray());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		fuel = nbt.getInt("fuel");
		revivedDead.clear();
		for (long l : nbt.getLongArray("revived_dead")) revivedDead.add(l);
		revivedFlickering.clear();
		for (long l : nbt.getLongArray("revived_flickering")) revivedFlickering.add(l);
	}
}
