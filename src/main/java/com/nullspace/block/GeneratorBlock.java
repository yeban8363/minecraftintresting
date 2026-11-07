package com.nullspace.block;

import com.mojang.serialization.MapCodec;
import com.nullspace.registry.ModBlockEntities;
import com.nullspace.registry.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Аварийный источник питания. Заправляется батарейками, редстоуном или углём.
 * Пока работает: светит, гудит, оживляет перегоревшие лампы вокруг
 * и восстанавливает Стабильность всем, кто рядом.
 */
public class GeneratorBlock extends BlockWithEntity {
	public static final MapCodec<GeneratorBlock> CODEC = createCodec(GeneratorBlock::new);
	public static final BooleanProperty LIT = Properties.LIT;

	public GeneratorBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(LIT, false));
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new GeneratorBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, ModBlockEntities.GENERATOR, GeneratorBlockEntity::tick);
	}

	/** Сколько тиков работы даёт предмет. */
	public static int fuelValue(ItemStack stack) {
		if (stack.isOf(ModItems.BATTERY)) return 20 * 150;
		if (stack.isOf(Items.REDSTONE_BLOCK)) return 20 * 270;
		if (stack.isOf(Items.REDSTONE)) return 20 * 30;
		if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)) return 20 * 60;
		return 0;
	}

	@Override
	protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		int fuel = fuelValue(stack);
		if (fuel <= 0) return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!world.isClient() && world.getBlockEntity(pos) instanceof GeneratorBlockEntity generator) {
			if (!generator.addFuel(fuel)) {
				player.sendMessage(Text.translatable("message.nullspace.generator_full").formatted(Formatting.GRAY), true);
				return ItemActionResult.CONSUME;
			}
			stack.decrementUnlessCreative(1, player);
			player.sendMessage(Text.translatable("message.nullspace.generator_fuel", generator.secondsLeft())
					.formatted(Formatting.GRAY), true);
		}
		return ItemActionResult.success(world.isClient());
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && world.getBlockEntity(pos) instanceof GeneratorBlockEntity generator) {
			Text text = generator.secondsLeft() > 0
					? Text.translatable("message.nullspace.generator_fuel", generator.secondsLeft())
					: Text.translatable("message.nullspace.generator_empty");
			player.sendMessage(text.copy().formatted(Formatting.GRAY), true);
		}
		return ActionResult.success(world.isClient());
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof GeneratorBlockEntity generator) {
			generator.revertLamps(world);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
