package com.nullspace.registry;

import com.nullspace.NullspaceMod;
import com.nullspace.block.AnomalyDoorBlock;
import com.nullspace.block.ChalkMarkBlock;
import com.nullspace.block.DampCarpetBlock;
import com.nullspace.block.CinemaScreenBlock;
import com.nullspace.block.DrainBlock;
import com.nullspace.block.EscalatorBlock;
import com.nullspace.block.MallMapBlock;
import com.nullspace.block.ElevatorBlock;
import com.nullspace.block.GeneratorBlock;
import com.nullspace.block.PaperPileBlock;
import com.nullspace.block.SupplyCrateBlock;
import com.nullspace.block.ExitDoorBlock;
import com.nullspace.block.FlickeringLightBlock;
import com.nullspace.block.GlowBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.TransparentBlock;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

import java.util.ArrayList;
import java.util.List;

public final class ModBlocks {
	/** Блоки, у которых есть предмет (для творческой вкладки). */
	public static final List<Block> WITH_ITEMS = new ArrayList<>();

	/** Стены и пол NULLSPACE в выживании не ломаются: пространство нельзя «прокопать». */
	private static AbstractBlock.Settings unbreakable(MapColor color, BlockSoundGroup sound) {
		return AbstractBlock.Settings.create()
				.mapColor(color)
				.strength(-1.0f, 3600000.0f)
				.dropsNothing()
				.allowsSpawning((state, world, pos, type) -> false)
				.pistonBehavior(PistonBehavior.BLOCK)
				.sounds(sound);
	}

	public static final Block OFFICE_WALLPAPER = register("office_wallpaper",
			new Block(unbreakable(MapColor.YELLOW, BlockSoundGroup.WOOD)));
	public static final Block OFFICE_WALLPAPER_BASE = register("office_wallpaper_base",
			new Block(unbreakable(MapColor.YELLOW, BlockSoundGroup.WOOD)));
	public static final Block OFFICE_CARPET = register("office_carpet",
			new Block(unbreakable(MapColor.PALE_YELLOW, BlockSoundGroup.WOOL)));
	public static final Block DAMP_CARPET = register("damp_carpet",
			new DampCarpetBlock(unbreakable(MapColor.BROWN, BlockSoundGroup.WOOL)));
	public static final Block CEILING_TILE = register("ceiling_tile",
			new Block(unbreakable(MapColor.WHITE_GRAY, BlockSoundGroup.STONE)));
	public static final Block CEILING_LIGHT = register("ceiling_light",
			new Block(unbreakable(MapColor.WHITE, BlockSoundGroup.GLASS).luminance(state -> 15)));
	public static final Block CEILING_LIGHT_OFF = register("ceiling_light_off",
			new Block(unbreakable(MapColor.GRAY, BlockSoundGroup.GLASS)));
	public static final Block CEILING_LIGHT_FLICKERING = register("ceiling_light_flickering",
			new FlickeringLightBlock(unbreakable(MapColor.WHITE, BlockSoundGroup.GLASS)
					.ticksRandomly()
					.luminance(state -> state.get(FlickeringLightBlock.LIT) ? 14 : 2)));
	public static final Block SUBFLOOR = register("subfloor",
			new Block(unbreakable(MapColor.STONE_GRAY, BlockSoundGroup.STONE)));
	public static final Block EXIT_DOOR = register("exit_door",
			new ExitDoorBlock(unbreakable(MapColor.IRON_GRAY, BlockSoundGroup.METAL)));
	public static final Block EXIT_SIGN = register("exit_sign",
			new Block(unbreakable(MapColor.EMERALD_GREEN, BlockSoundGroup.GLASS).luminance(state -> 10)));

	public static final Block GENERATOR = register("generator",
			new GeneratorBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.IRON_GRAY)
					.strength(3.5f, 6.0f)
					.requiresTool()
					.sounds(BlockSoundGroup.METAL)
					.luminance(state -> state.get(GeneratorBlock.LIT) ? 15 : 0)));
	public static final Block SUPPLY_CRATE = register("supply_crate",
			new SupplyCrateBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN)
					.strength(2.0f)
					.sounds(BlockSoundGroup.WOOD)));
	public static final Block PAPER_PILE = register("paper_pile",
			new PaperPileBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.WHITE)
					.strength(0.0f)
					.noCollision()
					.nonOpaque()
					.dropsNothing()
					.sounds(BlockSoundGroup.WOOL)
					.pistonBehavior(PistonBehavior.DESTROY)));
	public static final Block ANOMALY_DOOR = register("anomaly_door",
			new AnomalyDoorBlock(unbreakable(MapColor.OAK_TAN, BlockSoundGroup.WOOD)));

	// ---------- Уровень 1 — AQUATIC ----------
	public static final Block POOL_TILE = register("pool_tile",
			new Block(unbreakable(MapColor.WHITE, BlockSoundGroup.STONE)));
	public static final Block POOL_TILE_BLUE = register("pool_tile_blue",
			new Block(unbreakable(MapColor.LIGHT_BLUE, BlockSoundGroup.STONE)));
	public static final Block POOL_TILE_DARK = register("pool_tile_dark",
			new Block(unbreakable(MapColor.GRAY, BlockSoundGroup.STONE)));
	public static final Block POOL_LIGHT = register("pool_light",
			new Block(unbreakable(MapColor.LIGHT_BLUE, BlockSoundGroup.GLASS).luminance(state -> 12)));
	public static final Block DRAIN = register("drain",
			new DrainBlock(unbreakable(MapColor.IRON_GRAY, BlockSoundGroup.METAL)));

	// ---------- Уровень 2 — PARKING ----------
	public static final Block PARKING_CONCRETE = register("parking_concrete",
			new Block(unbreakable(MapColor.STONE_GRAY, BlockSoundGroup.STONE)));
	public static final Block ASPHALT = register("asphalt",
			new Block(unbreakable(MapColor.DEEPSLATE_GRAY, BlockSoundGroup.STONE)));
	public static final Block PAINT_WHITE = register("paint_white",
			new Block(unbreakable(MapColor.WHITE, BlockSoundGroup.STONE)));
	public static final Block PAINT_YELLOW = register("paint_yellow",
			new Block(unbreakable(MapColor.YELLOW, BlockSoundGroup.STONE)));
	public static final Block HAZARD_STRIPE = register("hazard_stripe",
			new Block(unbreakable(MapColor.YELLOW, BlockSoundGroup.STONE)));
	public static final Block HEADLIGHT = register("headlight",
			new Block(unbreakable(MapColor.WHITE, BlockSoundGroup.GLASS).luminance(state -> 12)));
	public static final Block HEADLIGHT_OFF = register("headlight_off",
			new Block(unbreakable(MapColor.LIGHT_GRAY, BlockSoundGroup.GLASS)));
	public static final Block TAILLIGHT = register("taillight",
			new Block(unbreakable(MapColor.RED, BlockSoundGroup.GLASS).luminance(state -> 4)));
	public static final Block SODIUM_LAMP = register("sodium_lamp",
			new Block(unbreakable(MapColor.ORANGE, BlockSoundGroup.GLASS).luminance(state -> 14)));
	public static final Block SODIUM_LAMP_OFF = register("sodium_lamp_off",
			new Block(unbreakable(MapColor.BROWN, BlockSoundGroup.GLASS)));
	public static final Block ELEVATOR = register("elevator",
			new ElevatorBlock(unbreakable(MapColor.IRON_GRAY, BlockSoundGroup.METAL)));

	// ---------- Уровень 3 — MALL ----------
	public static final Block MALL_FLOOR = register("mall_floor",
			new Block(unbreakable(MapColor.OFF_WHITE, BlockSoundGroup.STONE)));
	public static final Block MALL_FLOOR_DARK = register("mall_floor_dark",
			new Block(unbreakable(MapColor.TERRACOTTA_WHITE, BlockSoundGroup.STONE)));
	public static final Block MALL_WALL = register("mall_wall",
			new Block(unbreakable(MapColor.WHITE, BlockSoundGroup.STONE)));
	public static final Block SHOP_SIGN = register("shop_sign",
			new Block(unbreakable(MapColor.PINK, BlockSoundGroup.GLASS).luminance(state -> 11)));
	public static final Block ROLLER_SHUTTER = register("roller_shutter",
			new Block(unbreakable(MapColor.IRON_GRAY, BlockSoundGroup.METAL)));
	public static final Block TV_STATIC = register("tv_static",
			new Block(unbreakable(MapColor.GRAY, BlockSoundGroup.GLASS).luminance(state -> 7)));
	public static final Block CINEMA_SCREEN = register("cinema_screen",
			new CinemaScreenBlock(unbreakable(MapColor.WHITE, BlockSoundGroup.WOOL).luminance(state -> 9)));
	public static final Block SPEAKER = register("speaker",
			new Block(unbreakable(MapColor.GRAY, BlockSoundGroup.METAL)));
	public static final Block ESCALATOR = register("escalator",
			new EscalatorBlock(unbreakable(MapColor.IRON_GRAY, BlockSoundGroup.METAL).nonOpaque()));
	public static final Block ESCALATOR_BODY = register("escalator_body",
			new Block(unbreakable(MapColor.IRON_GRAY, BlockSoundGroup.METAL)));
	public static final Block MALL_MAP = register("mall_map",
			new MallMapBlock(unbreakable(MapColor.BLUE, BlockSoundGroup.GLASS).luminance(state -> 6)));

	/** Окно в стене Уровня 0. За ним — ты сам, со спины. */
	public static final Block ODD_WINDOW = register("odd_window",
			new TransparentBlock(unbreakable(MapColor.CLEAR, BlockSoundGroup.GLASS)
					.nonOpaque()
					.solidBlock((state, world, pos) -> false)
					.suffocates((state, world, pos) -> false)
					.blockVision((state, world, pos) -> false)));

	/** Метка мелом. Без предмета: ставится мелом. */
	public static final Block CHALK_MARK = registerNoItem("chalk_mark",
			new ChalkMarkBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.WHITE)
					.strength(0.0f)
					.noCollision()
					.nonOpaque()
					.replaceable()
					.dropsNothing()
					.sounds(BlockSoundGroup.SAND)
					.pistonBehavior(PistonBehavior.DESTROY)));

	/** Невидимый источник света от фонарика. Без предмета. */
	public static final Block GLOW = registerNoItem("glow",
			new GlowBlock(AbstractBlock.Settings.create()
					.replaceable()
					.noCollision()
					.dropsNothing()
					.nonOpaque()
					.noBlockBreakParticles()
					.ticksRandomly()
					.luminance(state -> 13)
					.pistonBehavior(PistonBehavior.DESTROY)));

	private static Block register(String name, Block block) {
		Registry.register(Registries.BLOCK, NullspaceMod.id(name), block);
		Registry.register(Registries.ITEM, NullspaceMod.id(name), new BlockItem(block, new Item.Settings()));
		WITH_ITEMS.add(block);
		return block;
	}

	private static Block registerNoItem(String name, Block block) {
		return Registry.register(Registries.BLOCK, NullspaceMod.id(name), block);
	}

	public static void register() {
	}

	private ModBlocks() {
	}
}
