package com.pizdec.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

/** Королевский магазин. Цены честные (по меркам короля). */
public final class Shop extends Gui {
	private record Offer(Supplier<ItemStack> stack, int price) {}

	private static final List<Offer> OFFERS = new ArrayList<>();

	private static void add(Item item, int count, int price) {
		OFFERS.add(new Offer(() -> new ItemStack(item, count), price));
	}

	private static void add(Supplier<ItemStack> st, int price) {
		OFFERS.add(new Offer(st, price));
	}

	static {
		// еда
		add(Items.BREAD, 4, 12);
		add(Items.COOKED_BEEF, 4, 25);
		add(Items.GOLDEN_CARROT, 4, 50);
		add(Items.CAKE, 1, 25);
		add(Items.GOLDEN_APPLE, 1, 90);
		add(Items.ENCHANTED_GOLDEN_APPLE, 1, 2500);
		// блоки
		add(Items.TORCH, 16, 10);
		add(Items.OAK_LOG, 16, 25);
		add(Items.STONE_BRICKS, 32, 20);
		add(Items.GLASS, 16, 20);
		add(Items.WHITE_BED, 1, 40);
		// ресурсы
		add(Items.IRON_INGOT, 4, 70);
		add(Items.GOLD_INGOT, 4, 110);
		add(Items.EMERALD, 1, 90);
		add(Items.DIAMOND, 1, 160);
		add(Items.NETHERITE_INGOT, 1, 1500);
		add(Items.EXPERIENCE_BOTTLE, 8, 50);
		// инструменты и оружие
		add(Items.IRON_PICKAXE, 1, 70);
		add(Items.DIAMOND_PICKAXE, 1, 450);
		add(Items.IRON_SWORD, 1, 60);
		add(Items.DIAMOND_SWORD, 1, 400);
		add(Items.BOW, 1, 45);
		add(Items.ARROW, 16, 15);
		add(Items.SHIELD, 1, 40);
		add(Items.FISHING_ROD, 1, 20);
		// броня
		add(Items.IRON_HELMET, 1, 55);
		add(Items.IRON_CHESTPLATE, 1, 90);
		add(Items.IRON_LEGGINGS, 1, 80);
		add(Items.IRON_BOOTS, 1, 45);
		add(Items.DIAMOND_CHESTPLATE, 1, 700);
		// разное
		add(Items.ENDER_PEARL, 2, 50);
		add(Items.WATER_BUCKET, 1, 25);
		add(Items.SADDLE, 1, 60);
		add(Items.NAME_TAG, 1, 60);
		add(Items.FIREWORK_ROCKET, 16, 70);
		add(Items.TOTEM_OF_UNDYING, 1, 1200);
		add(Items.ELYTRA, 1, 6000);
		add(Items.TNT, 1, 120);
		// особое
		add(Gui::freeCard, 350);
		add(Gui::fakeCard, 60);
		add(Gui::petRock, 5);
		add(Gui::fakeDiamond, 20);
		add(() -> Court.lawyerContract(1), 40);
		add(() -> Court.lawyerContract(2), 180);
		add(() -> Court.lawyerContract(3), 600);
	}

	private Shop(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
		super(syncId, pinv, inv, p);
		redraw();
	}

	public static void open(ServerPlayerEntity p) {
		if (Jail.jailed(p)) {
			U.msg(p, U.t("⛓ Из тюрьмы в магазин не ходят.", Formatting.GRAY));
			return;
		}
		Gui.open(p, "🛒 Королевский магазин", Shop::new);
	}

	/** Цена с учётом текущего указа. */
	private int price(int base) {
		double p = base * (1 - Ranks.shopDiscount(player));
		if (Laws.is("shop_sale")) p /= 2;
		if (Laws.is("shop_inflation")) p *= 3;
		return Math.max(1, (int) Math.round(p));
	}

	private void redraw() {
		fill(Items.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < OFFERS.size() && i < 45; i++) {
			Offer o = OFFERS.get(i);
			ItemStack st = o.stack.get();
			String name = st.getName().getString() + (st.getCount() > 1 ? " ×" + st.getCount() : "");
			set(i, icon(st, "§f" + name, "§6Цена: " + price(o.price) + " бабла", "§7ЛКМ — купить"));
		}
		set(49, icon(Items.GOLD_NUGGET, "§6Твоё бабло: " + Money.get(player)));
		String note = Laws.is("shop_sale") ? "§aСейчас распродажа −50%!"
				: Laws.is("shop_inflation") ? "§cИнфляция: цены ×3" : "§7Цены утверждены королём";
		set(48, icon(Items.PAPER, note));
		set(47, icon(Items.GOLDEN_HELMET, "§d👑 Магазин званий", "§7Твоё звание: " + Ranks.name(player),
				"§7Скидка по званию: " + (int) (Ranks.shopDiscount(player) * 100) + "%"));
		set(53, icon(Items.BARRIER, "§cЗакрыть"));
	}

	@Override
	protected void click(int slot, int button) {
		if (slot == 53) {
			close();
			return;
		}
		if (slot == 47) {
			Ranks.ShopGui.open(player);
			return;
		}
		if (slot >= OFFERS.size() || slot >= 45) return;
		Offer o = OFFERS.get(slot);
		int cost = price(o.price);
		if (Laws.is("shop_closed")) {
			U.bar(player, U.t("🛒 Магазин закрыт на учёт. Указ короля.", Formatting.RED));
			return;
		}
		if (Money.pay(player, cost, "покупку")) {
			player.getInventory().offerOrDrop(o.stack.get());
			Laws.onBuy(player);
			if (Laws.is("cashback") && cost >= 4) Money.give(player, cost * 3 / 10, "кэшбэк");
			U.sound(player, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
			redraw();
		} else if (Money.get(player) < 0) {
			close();
			Jail.arrest(player, "попытка купить в долг", 20);
		} else {
			redraw();
		}
	}

	@Override
	protected void tick() {
		if (age % 20 == 0) redraw();
	}
}
