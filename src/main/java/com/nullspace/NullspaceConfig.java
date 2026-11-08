package com.nullspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Простой конфиг: config/nullspace.json. Создаётся при первом запуске.
 * Все шансы указаны «за одну проверку», проверка идёт раз в секунду.
 */
public class NullspaceConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Шанс провалиться, пока игрок задыхается внутри блока. */
	public double chanceInsideWall = 0.01;
	/** Шанс провалиться в полной темноте под землёй (≈ раз в 3 часа темноты при 0.0001). */
	public double chanceDarkness = 0.0001;
	/** Ниже какой высоты считаем «под землёй». */
	public int darknessMaxY = 40;
	/** Минимум/максимум тиков предвестников перед провалом. */
	public int precursorMinTicks = 100;
	public int precursorMaxTicks = 400;
	/** Доля стаков основного инвентаря (не хотбар), которые «не проходят» и остаются в Следе. */
	public double itemLossChance = 0.2;
	/** Радиус (в блоках) случайной точки появления на Уровне 0. */
	public int spawnSpread = 200000;
	/** Сколько секунд работает полный фонарик. */
	public int flashlightSeconds = 600;
	/** Шанс, что батарейка окажется севшей. */
	public double deadBatteryChance = 0.15;
	/** Шанс появления двери EXIT на отрезке стены (1 / exitRarity). */
	public int exitRarity = 2500;
	/** Шанс аномальной двери (в стадион) на отрезке стены (1 / anomalyDoorRarity). */
	public int anomalyDoorRarity = 450;
	/** Строить ли на Уровне 0 «копию дома» — комнату из блоков места, откуда ты упал. */
	public boolean baseCopy = true;
	/** Включить ли события атмосферы (шаги, стук, тишина). */
	public boolean atmosphere = true;

	private static NullspaceConfig instance = new NullspaceConfig();

	public static NullspaceConfig get() {
		return instance;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("nullspace.json");
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					NullspaceConfig loaded = GSON.fromJson(reader, NullspaceConfig.class);
					if (loaded != null) instance = loaded;
				}
			}
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(instance, writer);
			}
		} catch (Exception e) {
			NullspaceMod.LOGGER.error("Не удалось прочитать config/nullspace.json, используются значения по умолчанию", e);
		}
	}
}
