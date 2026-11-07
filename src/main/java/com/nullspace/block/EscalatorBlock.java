package com.nullspace.block;

import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Ступень эскалатора. Работает без электричества.
 *
 * FACING  — куда эскалатор поднимается (там у ступени высокая часть).
 * DOWN    — лента идёт вниз (тянет против FACING). Иногда эскалатор сам меняет направление.
 * ENDLESS — верхняя ступень бесконечного эскалатора: дойдя до неё,
 *           оказываешься снова внизу. Сойти можно только сбоку или назад.
 */
public class EscalatorBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final BooleanProperty DOWN = BooleanProperty.of("down");
	public static final BooleanProperty ENDLESS = BooleanProperty.of("endless");

	private static final VoxelShape BOTTOM = Block.createCuboidShape(0, 0, 0, 16, 8, 16);
	private static final VoxelShape NORTH = VoxelShapes.union(BOTTOM, Block.createCuboidShape(0, 8, 0, 16, 16, 8));
	private static final VoxelShape SOUTH = VoxelShapes.union(BOTTOM, Block.createCuboidShape(0, 8, 8, 16, 16, 16));
	private static final VoxelShape EAST = VoxelShapes.union(BOTTOM, Block.createCuboidShape(8, 8, 0, 16, 16, 16));
	private static final VoxelShape WEST = VoxelShapes.union(BOTTOM, Block.createCuboidShape(0, 8, 0, 8, 16, 16));
	private static final double SPEED = 0.045;
	private static final double MAX = 0.22;

	public EscalatorBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(DOWN, false).with(ENDLESS, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, DOWN, ENDLESS);
	}

	private static VoxelShape shape(BlockState state) {
		return switch (state.get(FACING)) {
			case SOUTH -> SOUTH;
			case EAST -> EAST;
			case WEST -> WEST;
			default -> NORTH;
		};
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shape(state);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shape(state);
	}

	@Override
	public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
		Direction up = state.get(FACING);
		Direction dir = state.get(DOWN) ? up.getOpposite() : up;

		Vec3d v = entity.getVelocity();
		double along = v.x * dir.getOffsetX() + v.z * dir.getOffsetZ();
		if (along < MAX) {
			entity.setVelocity(v.x + dir.getOffsetX() * SPEED, v.y, v.z + dir.getOffsetZ() * SPEED);
		}

		if (!world.isClient() && state.get(ENDLESS) && !state.get(DOWN) && entity instanceof PlayerEntity) {
			// Шесть ступеней вниз и назад — туда, откуда начинал.
			entity.requestTeleport(entity.getX() - up.getOffsetX() * 6, entity.getY() - 6, entity.getZ() - up.getOffsetZ() * 6);
		}
		super.onSteppedOn(world, pos, state, entity);
	}

	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (random.nextInt(60) == 0) {
			world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, ModSounds.ESCALATOR_HUM,
					SoundCategory.BLOCKS, 0.35f, 0.9f + random.nextFloat() * 0.2f, false);
		}
	}
}
