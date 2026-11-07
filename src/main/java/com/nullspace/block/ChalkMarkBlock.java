package com.nullspace.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Метка мелом на стене (как лестница: FACING смотрит от стены).
 * STRANGER = true — стрелка, которую нарисовал не ты.
 */
public class ChalkMarkBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final BooleanProperty STRANGER = BooleanProperty.of("stranger");

	private static final VoxelShape NORTH = Block.createCuboidShape(0, 0, 15, 16, 16, 16);
	private static final VoxelShape SOUTH = Block.createCuboidShape(0, 0, 0, 16, 16, 1);
	private static final VoxelShape EAST = Block.createCuboidShape(0, 0, 0, 1, 16, 16);
	private static final VoxelShape WEST = Block.createCuboidShape(15, 0, 0, 16, 16, 16);

	public ChalkMarkBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(STRANGER, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, STRANGER);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return switch (state.get(FACING)) {
			case SOUTH -> SOUTH;
			case EAST -> EAST;
			case WEST -> WEST;
			default -> NORTH;
		};
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return VoxelShapes.empty();
	}
}
