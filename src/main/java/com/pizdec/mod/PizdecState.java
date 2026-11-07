package com.pizdec.mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** Всё, что должно пережить перезапуск: постройки, сидельцы, звания, работа, прописка, война. */
public class PizdecState extends PersistentState {
	public boolean castleBuilt;
	public int cx, cy, cz;
	public boolean prisonBuilt;
	public int px, py, pz;
	public boolean stallsBuilt;
	public boolean courtBuilt;
	public int kx, ky, kz;
	public boolean warBuilt;
	public int wx, wy, wz;
	public int version;
	public int decrees;
	public int taxRaids;
	public int arrests;

	/** Адрес по прописке: табличка. */
	public static final class Address {
		public int x, y, z;
		public String name;

		public Address(int x, int y, int z, String name) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.name = name;
		}
	}

	public final Map<UUID, Jail.Record> jail = new HashMap<>();
	public final Map<UUID, Integer> exiles = new HashMap<>();
	public final Map<UUID, Integer> ranks = new HashMap<>();
	public final Map<UUID, String> jobs = new HashMap<>();
	public final Map<UUID, Integer> jobMisses = new HashMap<>();
	public final Map<UUID, Address> addresses = new HashMap<>();
	public final Map<UUID, String> names = new HashMap<>();

	/** Аренда номера в отеле: номер → кто и до какого мирового тика. */
	public static final class Rental {
		public UUID who;
		public long until;

		public Rental(UUID who, long until) {
			this.who = who;
			this.until = until;
		}
	}

	public final Map<Integer, Rental> hotel = new HashMap<>();

	private static final Type<PizdecState> TYPE = new Type<>(PizdecState::new, PizdecState::fromNbt, null);

	public static PizdecState get(MinecraftServer s) {
		return s.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "pizdec");
	}

	private interface Reader {
		void read(UUID id, NbtCompound c);
	}

	private static void readMap(NbtCompound root, String key, Reader r) {
		NbtCompound m = root.getCompound(key);
		for (String k : m.getKeys()) {
			try {
				r.read(UUID.fromString(k), m.getCompound(k));
			} catch (IllegalArgumentException ignored) {
				// битая запись — пропускаем
			}
		}
	}

	private static PizdecState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		PizdecState st = new PizdecState();
		st.castleBuilt = nbt.getBoolean("castleBuilt");
		st.cx = nbt.getInt("cx");
		st.cy = nbt.getInt("cy");
		st.cz = nbt.getInt("cz");
		st.prisonBuilt = nbt.getBoolean("prisonBuilt");
		st.px = nbt.getInt("px");
		st.py = nbt.getInt("py");
		st.pz = nbt.getInt("pz");
		st.stallsBuilt = nbt.getBoolean("stallsBuilt");
		st.courtBuilt = nbt.getBoolean("courtBuilt");
		st.kx = nbt.getInt("kx");
		st.ky = nbt.getInt("ky");
		st.kz = nbt.getInt("kz");
		st.warBuilt = nbt.getBoolean("warBuilt");
		st.wx = nbt.getInt("wx");
		st.wy = nbt.getInt("wy");
		st.wz = nbt.getInt("wz");
		st.version = nbt.getInt("version");
		st.decrees = nbt.getInt("decrees");
		st.taxRaids = nbt.getInt("taxRaids");
		st.arrests = nbt.getInt("arrests");
		readMap(nbt, "jail", (id, c) -> st.jail.put(id, new Jail.Record(c.getInt("t"), c.getString("crime"), c.getInt("cell"))));
		readMap(nbt, "exiles", (id, c) -> st.exiles.put(id, c.getInt("t")));
		readMap(nbt, "ranks", (id, c) -> st.ranks.put(id, c.getInt("r")));
		readMap(nbt, "jobs", (id, c) -> {
			st.jobs.put(id, c.getString("job"));
			st.jobMisses.put(id, c.getInt("miss"));
		});
		readMap(nbt, "addresses", (id, c) -> st.addresses.put(id,
				new Address(c.getInt("x"), c.getInt("y"), c.getInt("z"), c.getString("name"))));
		readMap(nbt, "names", (id, c) -> st.names.put(id, c.getString("n")));
		NbtCompound h = nbt.getCompound("hotel");
		for (String k : h.getKeys()) {
			try {
				NbtCompound c = h.getCompound(k);
				st.hotel.put(Integer.parseInt(k), new Rental(UUID.fromString(c.getString("who")), c.getLong("until")));
			} catch (IllegalArgumentException ignored) {
				// пропускаем
			}
		}
		return st;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		nbt.putBoolean("castleBuilt", castleBuilt);
		nbt.putInt("cx", cx);
		nbt.putInt("cy", cy);
		nbt.putInt("cz", cz);
		nbt.putBoolean("prisonBuilt", prisonBuilt);
		nbt.putInt("px", px);
		nbt.putInt("py", py);
		nbt.putInt("pz", pz);
		nbt.putBoolean("stallsBuilt", stallsBuilt);
		nbt.putBoolean("courtBuilt", courtBuilt);
		nbt.putInt("kx", kx);
		nbt.putInt("ky", ky);
		nbt.putInt("kz", kz);
		nbt.putBoolean("warBuilt", warBuilt);
		nbt.putInt("wx", wx);
		nbt.putInt("wy", wy);
		nbt.putInt("wz", wz);
		nbt.putInt("version", version);
		nbt.putInt("decrees", decrees);
		nbt.putInt("taxRaids", taxRaids);
		nbt.putInt("arrests", arrests);

		NbtCompound j = new NbtCompound();
		jail.forEach((id, r) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("t", r.ticks);
			c.putString("crime", r.crime);
			c.putInt("cell", r.cell);
			j.put(id.toString(), c);
		});
		nbt.put("jail", j);

		NbtCompound e = new NbtCompound();
		exiles.forEach((id, t) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("t", t);
			e.put(id.toString(), c);
		});
		nbt.put("exiles", e);

		NbtCompound r = new NbtCompound();
		ranks.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("r", v);
			r.put(id.toString(), c);
		});
		nbt.put("ranks", r);

		NbtCompound jb = new NbtCompound();
		jobs.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putString("job", v);
			c.putInt("miss", jobMisses.getOrDefault(id, 0));
			jb.put(id.toString(), c);
		});
		nbt.put("jobs", jb);

		NbtCompound a = new NbtCompound();
		addresses.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("x", v.x);
			c.putInt("y", v.y);
			c.putInt("z", v.z);
			c.putString("name", v.name);
			a.put(id.toString(), c);
		});
		nbt.put("addresses", a);

		NbtCompound n = new NbtCompound();
		names.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putString("n", v);
			n.put(id.toString(), c);
		});
		nbt.put("names", n);

		NbtCompound h = new NbtCompound();
		hotel.forEach((room, r) -> {
			NbtCompound c = new NbtCompound();
			c.putString("who", r.who.toString());
			c.putLong("until", r.until);
			h.put(String.valueOf(room), c);
		});
		nbt.put("hotel", h);
		return nbt;
	}
}
