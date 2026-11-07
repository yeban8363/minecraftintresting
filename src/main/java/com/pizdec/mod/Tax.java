package com.pizdec.mod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

/** Налоговая служба. Приходит каждые 5 минут. Всегда. */
public final class Tax {
	public static final int PERIOD = 20 * 60 * 5;
	public static final String COLLECTOR_TAG = "pizdec_collector";

	/** Множитель налога на следующий визит (законы короля его меняют). */
	public static double multiplier = 1.0;
	public static boolean amnesty = false;

	private static final String[] REASONS = {
			"налог на дыхание", "сбор за то, что ты существуешь", "НДС на блоки",
			"налог на квадратность", "акциз на картошку", "пошлина на прыжки",
			"взнос на новую корону короля", "налог на наличие инвентаря", "сбор за использование солнца",
			"налог на ночной сон", "штраф за подозрительно счастливое лицо", "налог на налоги",
			"сбор на ремонт замка, который никто не ремонтирует", "налог на крипера, который взорвал твой дом",
			"плата за просмотр неба", "налог на гравитацию", "налог на то, что ты не заплатил налог"
	};

	private Tax() {}

	public static void warn(MinecraftServer s) {
		U.say(s, U.t("⚠ Через 30 секунд придёт НАЛОГОВАЯ. Прячьте бабло. Хотя не поможет.", Formatting.RED));
		U.soundAll(s, SoundEvents.BLOCK_BELL_USE, 1f, 0.5f);
	}

	public static void raid(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		st.taxRaids++;
		st.markDirty();

		if (amnesty) {
			amnesty = false;
			multiplier = 1.0;
			U.titleAll(s, U.t("НАЛОГОВАЯ", Formatting.GREEN, Formatting.BOLD),
					U.t("…сегодня бухает. Амнистия! Повезло, нищеброды.", Formatting.GREEN));
			return;
		}

		U.titleAll(s, U.t("💸 НАЛОГОВАЯ 💸", Formatting.DARK_RED, Formatting.BOLD),
				U.t("Проверка №" + st.taxRaids + ". Всем стоять!", Formatting.RED));
		U.soundAll(s, SoundEvents.EVENT_RAID_HORN, 4f, 1f);

		for (ServerPlayerEntity p : U.players(s)) taxPlayer(p);
		multiplier = 1.0;
	}

	private static void taxPlayer(ServerPlayerEntity p) {
		String why = U.pick(REASONS);
		int bal = Money.get(p);
		int tax = (int) Math.max(10, Math.round(Math.max(bal, 0) * 0.2 * multiplier) + 10 * multiplier);

		U.msg(p, U.t("🧾 Налоговая: «" + why + "» — " + tax + " бабла.", Formatting.GOLD));

		if (bal >= tax) {
			Money.add(p, -tax);
			U.msg(p, U.t("   Списано " + tax + ". Остаток: " + Money.get(p) + ". Спасибо за сотрудничество.",
					Formatting.GRAY));
			return;
		}

		// бабла не хватает — забираем имущество
		int missing = tax - Math.max(bal, 0);
		if (bal > 0) Money.set(p, 0);
		int stacksToTake = Math.min(3, 1 + missing / 25);
		List<String> taken = confiscate(p, stacksToTake);
		if (!taken.isEmpty()) {
			U.msg(p, U.t("   Бабла не хватило. Имущество арестовано: " + String.join(", ", taken), Formatting.RED));
			U.sound(p, SoundEvents.BLOCK_CHAIN_BREAK, 1f, 0.5f);
		} else {
			// брать нечего — вешаем долг и шлём коллектора
			Money.add(p, -missing);
			U.msg(p, U.t("   Брать нечего. Долг: " + missing + ". За тобой выехал коллектор.", Formatting.DARK_RED,
					Formatting.BOLD));
		}
		if (Money.get(p) < 0) sendCollector(p);
	}

	/** Забирает случайные стаки из инвентаря. Возвращает список того, что забрали. */
	public static List<String> confiscate(ServerPlayerEntity p, int count) {
		PlayerInventory inv = p.getInventory();
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < inv.size(); i++) {
			if (!inv.getStack(i).isEmpty()) slots.add(i);
		}
		List<String> out = new ArrayList<>();
		for (int n = 0; n < count && !slots.isEmpty(); n++) {
			int slot = slots.remove(U.R.nextInt(slots.size()));
			ItemStack st = inv.getStack(slot);
			out.add(st.getCount() + "× " + st.getName().getString());
			inv.setStack(slot, ItemStack.EMPTY);
		}
		return out;
	}

	public static void sendCollector(ServerPlayerEntity p) {
		VindicatorEntity v = U.spawnNear(EntityType.VINDICATOR, p, 6);
		if (v == null) return;
		v.setCustomName(U.t(U.pick("Коллектор Гена", "Коллектор Толик", "Коллектор Жора", "Пристав Михалыч"),
				Formatting.DARK_RED));
		v.setCustomNameVisible(true);
		v.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
		v.addCommandTag(COLLECTOR_TAG);
		v.setTarget(p);
		U.sound(p, SoundEvents.ENTITY_VINDICATOR_CELEBRATE, 1.5f, 0.8f);
	}

	public static int secondsLeft(int ticksSinceRaid) {
		return Math.max(0, (PERIOD - ticksSinceRaid) / 20);
	}
}
