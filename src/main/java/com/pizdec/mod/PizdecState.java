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
	public boolean mfcBuilt;
	public int mx, my, mz;
	public int passportSeq;
	public int newsIssue;
	public boolean portraitsPlaced;
	public boolean borderBuilt;
	public int warrantNo;
	/** Подозрительность (тайная полиция): 0..100. */
	public final Map<UUID, Integer> suspicion = new HashMap<>();
	/** Досье: последние заметки тайной полиции. */
	public final Map<UUID, java.util.List<String>> dossier = new HashMap<>();
	/** Члены подполья «Свободная Картошка». */
	public final java.util.Set<UUID> underground = new java.util.HashSet<>();
	/** Разоблачённые подпольщики, которых не было в сети, — арест при входе. */
	public final java.util.Set<UUID> wanted = new java.util.HashSet<>();
	/** За границей: UUID → до какого мирового тика (отрицательное — нелегально). */
	public final Map<UUID, Long> abroad = new HashMap<>();
	/** Завербованные осведомители. */
	public final java.util.Set<UUID> informants = new java.util.HashSet<>();
	/** Высота земли у четырёх КПП. */
	public int[] borderY = new int[4];
	/** Места портретов: "variant;x;y;z;facing". */
	public final java.util.List<String> portraitSpots = new java.util.ArrayList<>();
	public int electionNo;
	public int electionTicks;
	public int billNo;
	public int[] seats = new int[7];
	public String lastResult = "";
	/** Голоса на текущих выборах: UUID → индекс партии. */
	public final Map<UUID, Integer> votes = new HashMap<>();
	public int cadastralSeq;
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

	/** Паспорт гражданина. */
	public static final class Passport {
		public String series = "";
		public int number;
		public String fio = "";
		public String gender = "";
		public String birth = "";
		public String issued = "";
		public String regName = "";
		public int regX, regY, regZ;
		public String regDate = "";
		public int version;
	}

	/** Объект недвижимости (жилой дом с участком). */
	public static final class Property {
		public String cadastral = "";
		public int x0, y0, z0, x1, y1, z1;
		public String addr = "";
		public String regDate = "";
		/** 0 — в порядке, 1 — арест (долги). */
		public int status;
		public String reason = "";
		public int value;
		public int version;
		public int decree;
		public final java.util.List<UUID> residents = new java.util.ArrayList<>();

		public boolean contains(int x, int y, int z) {
			return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
		}

		public int area() {
			return (x1 - x0 + 1) * (z1 - z0 + 1);
		}
	}

	public final Map<UUID, Passport> passports = new HashMap<>();
	public final Map<UUID, Property> properties = new HashMap<>();
	/** Конфискованное в пользу Короны (выставлено на торги). */
	public final java.util.List<Property> crown = new java.util.ArrayList<>();
	/** Кому раньше принадлежал конфискованный объект: кадастровый № → UUID. */
	public final Map<String, UUID> formerOwners = new HashMap<>();
	/** Судимости: последние 10 приговоров. */
	public final Map<UUID, java.util.List<String>> crimes = new HashMap<>();

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
		st.mfcBuilt = nbt.getBoolean("mfcBuilt");
		st.mx = nbt.getInt("mx");
		st.my = nbt.getInt("my");
		st.mz = nbt.getInt("mz");
		st.passportSeq = nbt.getInt("passportSeq");
		st.newsIssue = nbt.getInt("newsIssue");
		st.portraitsPlaced = nbt.getBoolean("portraitsPlaced");
		st.borderBuilt = nbt.getBoolean("borderBuilt");
		st.warrantNo = nbt.getInt("warrantNo");
		readMap(nbt, "suspicion", (id, c) -> st.suspicion.put(id, c.getInt("v")));
		readMap(nbt, "dossier", (id, c) -> {
			java.util.List<String> l = new java.util.ArrayList<>();
			for (String line : c.getString("list").split("\n")) if (!line.isBlank()) l.add(line);
			st.dossier.put(id, l);
		});
		readMap(nbt, "underground", (id, c) -> st.underground.add(id));
		readMap(nbt, "wantedList", (id, c) -> st.wanted.add(id));
		readMap(nbt, "abroad", (id, c) -> st.abroad.put(id, c.getLong("until")));
		readMap(nbt, "informants", (id, c) -> st.informants.add(id));
		int[] by = nbt.getIntArray("borderY");
		if (by.length == 4) st.borderY = by;
		for (String sp : nbt.getString("portraitSpots").split("\n")) if (!sp.isBlank()) st.portraitSpots.add(sp);
		st.electionNo = nbt.getInt("electionNo");
		st.electionTicks = nbt.getInt("electionTicks");
		st.billNo = nbt.getInt("billNo");
		int[] seats = nbt.getIntArray("seats");
		if (seats.length == 7) st.seats = seats;
		st.lastResult = nbt.getString("lastResult");
		readMap(nbt, "votes", (id, c) -> st.votes.put(id, c.getInt("p")));
		st.cadastralSeq = nbt.getInt("cadastralSeq");
		st.decrees = nbt.getInt("decrees");
		st.taxRaids = nbt.getInt("taxRaids");
		st.arrests = nbt.getInt("arrests");
		readMap(nbt, "jail", (id, c) -> {
			Jail.Record r = new Jail.Record(c.getInt("t"), c.getString("crime"), c.getInt("cell"));
			r.severity = c.getInt("sev");
			st.jail.put(id, r);
		});
		readMap(nbt, "passports", (id, c) -> {
			Passport p = new Passport();
			p.series = c.getString("series");
			p.number = c.getInt("number");
			p.fio = c.getString("fio");
			p.gender = c.getString("gender");
			p.birth = c.getString("birth");
			p.issued = c.getString("issued");
			p.regName = c.getString("regName");
			p.regX = c.getInt("regX");
			p.regY = c.getInt("regY");
			p.regZ = c.getInt("regZ");
			p.regDate = c.getString("regDate");
			p.version = c.getInt("ver");
			st.passports.put(id, p);
		});
		readMap(nbt, "properties", (id, c) -> st.properties.put(id, readProperty(c)));
		NbtCompound cr = nbt.getCompound("crownList");
		for (String k : cr.getKeys()) {
			NbtCompound c = cr.getCompound(k);
			st.crown.add(readProperty(c));
			if (c.contains("former")) {
				try {
					st.formerOwners.put(c.getString("cadastral"), UUID.fromString(c.getString("former")));
				} catch (IllegalArgumentException ignored) {
					// нет так нет
				}
			}
		}
		readMap(nbt, "crimes", (id, c) -> {
			java.util.List<String> l = new java.util.ArrayList<>();
			for (String line : c.getString("list").split("\n")) if (!line.isBlank()) l.add(line);
			st.crimes.put(id, l);
		});
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

	private static Property readProperty(NbtCompound c) {
		Property p = new Property();
		p.cadastral = c.getString("cadastral");
		p.x0 = c.getInt("x0");
		p.y0 = c.getInt("y0");
		p.z0 = c.getInt("z0");
		p.x1 = c.getInt("x1");
		p.y1 = c.getInt("y1");
		p.z1 = c.getInt("z1");
		p.addr = c.getString("addr");
		p.regDate = c.getString("regDate");
		p.status = c.getInt("status");
		p.reason = c.getString("reason");
		p.value = c.getInt("value");
		p.version = c.getInt("ver");
		p.decree = c.getInt("decree");
		for (String r : c.getString("residents").split(",")) {
			try {
				if (!r.isBlank()) p.residents.add(UUID.fromString(r));
			} catch (IllegalArgumentException ignored) {
				// пропуск
			}
		}
		return p;
	}

	private static NbtCompound writeProperty(Property p) {
		NbtCompound c = new NbtCompound();
		c.putString("cadastral", p.cadastral);
		c.putInt("x0", p.x0);
		c.putInt("y0", p.y0);
		c.putInt("z0", p.z0);
		c.putInt("x1", p.x1);
		c.putInt("y1", p.y1);
		c.putInt("z1", p.z1);
		c.putString("addr", p.addr);
		c.putString("regDate", p.regDate);
		c.putInt("status", p.status);
		c.putString("reason", p.reason);
		c.putInt("value", p.value);
		c.putInt("ver", p.version);
		c.putInt("decree", p.decree);
		StringBuilder sb = new StringBuilder();
		for (UUID u : p.residents) sb.append(sb.length() > 0 ? "," : "").append(u);
		c.putString("residents", sb.toString());
		return c;
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
		nbt.putBoolean("mfcBuilt", mfcBuilt);
		nbt.putInt("mx", mx);
		nbt.putInt("my", my);
		nbt.putInt("mz", mz);
		nbt.putInt("passportSeq", passportSeq);
		nbt.putInt("newsIssue", newsIssue);
		nbt.putBoolean("portraitsPlaced", portraitsPlaced);
		nbt.putBoolean("borderBuilt", borderBuilt);
		nbt.putInt("warrantNo", warrantNo);
		NbtCompound su = new NbtCompound();
		suspicion.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("v", v);
			su.put(id.toString(), c);
		});
		nbt.put("suspicion", su);
		NbtCompound ds = new NbtCompound();
		dossier.forEach((id, l) -> {
			NbtCompound c = new NbtCompound();
			c.putString("list", String.join("\n", l));
			ds.put(id.toString(), c);
		});
		nbt.put("dossier", ds);
		NbtCompound ug = new NbtCompound();
		underground.forEach(id -> ug.put(id.toString(), new NbtCompound()));
		nbt.put("underground", ug);
		NbtCompound wl = new NbtCompound();
		wanted.forEach(id -> wl.put(id.toString(), new NbtCompound()));
		nbt.put("wantedList", wl);
		NbtCompound ab = new NbtCompound();
		abroad.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putLong("until", v);
			ab.put(id.toString(), c);
		});
		nbt.put("abroad", ab);
		NbtCompound inf = new NbtCompound();
		informants.forEach(id -> inf.put(id.toString(), new NbtCompound()));
		nbt.put("informants", inf);
		nbt.putIntArray("borderY", borderY);
		nbt.putString("portraitSpots", String.join("\n", portraitSpots));
		nbt.putInt("electionNo", electionNo);
		nbt.putInt("electionTicks", electionTicks);
		nbt.putInt("billNo", billNo);
		nbt.putIntArray("seats", seats);
		nbt.putString("lastResult", lastResult);
		NbtCompound vt = new NbtCompound();
		votes.forEach((id, v) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("p", v);
			vt.put(id.toString(), c);
		});
		nbt.put("votes", vt);
		nbt.putInt("cadastralSeq", cadastralSeq);
		nbt.putInt("decrees", decrees);
		nbt.putInt("taxRaids", taxRaids);
		nbt.putInt("arrests", arrests);

		NbtCompound j = new NbtCompound();
		jail.forEach((id, r) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("t", r.ticks);
			c.putString("crime", r.crime);
			c.putInt("cell", r.cell);
			c.putInt("sev", r.severity);
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
		hotel.forEach((room, rent) -> {
			NbtCompound c = new NbtCompound();
			c.putString("who", rent.who.toString());
			c.putLong("until", rent.until);
			h.put(String.valueOf(room), c);
		});
		nbt.put("hotel", h);

		NbtCompound ps = new NbtCompound();
		passports.forEach((id, p) -> {
			NbtCompound c = new NbtCompound();
			c.putString("series", p.series);
			c.putInt("number", p.number);
			c.putString("fio", p.fio);
			c.putString("gender", p.gender);
			c.putString("birth", p.birth);
			c.putString("issued", p.issued);
			c.putString("regName", p.regName);
			c.putInt("regX", p.regX);
			c.putInt("regY", p.regY);
			c.putInt("regZ", p.regZ);
			c.putString("regDate", p.regDate);
			c.putInt("ver", p.version);
			ps.put(id.toString(), c);
		});
		nbt.put("passports", ps);

		NbtCompound pr = new NbtCompound();
		properties.forEach((id, p) -> pr.put(id.toString(), writeProperty(p)));
		nbt.put("properties", pr);

		NbtCompound cr = new NbtCompound();
		for (int i = 0; i < crown.size(); i++) {
			NbtCompound c = writeProperty(crown.get(i));
			UUID former = formerOwners.get(crown.get(i).cadastral);
			if (former != null) c.putString("former", former.toString());
			cr.put(String.valueOf(i), c);
		}
		nbt.put("crownList", cr);

		NbtCompound cm = new NbtCompound();
		crimes.forEach((id, l) -> {
			NbtCompound c = new NbtCompound();
			c.putString("list", String.join("\n", l));
			cm.put(id.toString(), c);
		});
		nbt.put("crimes", cm);
		return nbt;
	}
}
