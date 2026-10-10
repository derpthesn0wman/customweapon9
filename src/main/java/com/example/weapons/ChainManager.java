package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The chain sword:
 *  - hit the same target N times with FULLY CHARGED swings and they get chained in place for a while
 *    (an action-bar meter, like the spear's charge bar, counts the stack)
 *  - CROUCH + hit two different people and they get chained together by a red chain: damage to one is
 *    also dealt to the other
 *
 * Config (on the weapon):
 *   chain:
 *     hits, hit-timeout, require-full-charge, gui, duration
 *     damage-link: require-crouch, window, duration, cooldown, share, true-damage, include-mobs
 */
public class ChainManager implements Listener {

    // Iron chain (like the Minecraft chain block) and the red link chain
    private static final Color IRON_DARK = Color.fromRGB(58, 58, 66);
    private static final Color IRON_LIGHT = Color.fromRGB(150, 150, 162);
    private static final Color RED_DARK = Color.fromRGB(105, 0, 0);
    private static final Color RED_LIGHT = Color.fromRGB(255, 55, 55);
    private static final Color GOLD_DARK = Color.fromRGB(160, 110, 0);
    private static final Color GOLD_LIGHT = Color.fromRGB(255, 205, 50);

    /** A partial swing deals 20% + 80% x strength^2 of full damage, so 0.97 means "essentially fully charged". */
    private static final double FULL_CHARGE_RATIO = 0.97;

    private final WeaponPlugin plugin;
    private final WeaponManager manager;
    private final Attribute attackDamage;

    private final Map<UUID, Map<UUID, HitCount>> hitCounts = new HashMap<>(); // attacker -> target -> hits
    private final Map<UUID, Long> chained = new HashMap<>();                   // entity -> chained until (ms)
    private final Map<UUID, Link> links = new HashMap<>();                     // entity -> link (both ends)
    private final Map<UUID, LastHit> lastHits = new HashMap<>();               // attacker -> marked target (crouch hit)
    private final Map<UUID, Long> linkCooldowns = new HashMap<>();             // attacker -> next allowed link (ms)
    private final Map<UUID, CountDisplay> countBars = new HashMap<>();         // attacker -> chain stack action bar
    private final Map<Link, BossBar> linkBars = new HashMap<>();               // link -> countdown boss bar
    private boolean mirroring = false;
    private BukkitTask task;

    private static class HitCount {
        int count = 0;
        long last = 0;
    }

    private static class LastHit {
        final UUID target;
        final long time;

        LastHit(UUID target, long time) {
            this.target = target;
            this.time = time;
        }
    }

    private static class Link {
        final UUID a;
        final UUID b;
        final UUID owner;
        final long start;
        final long until;
        final double share;
        final boolean trueDamage;

        Link(UUID a, UUID b, UUID owner, long start, long until, double share, boolean trueDamage) {
            this.a = a;
            this.b = b;
            this.owner = owner;
            this.start = start;
            this.until = until;
            this.share = share;
            this.trueDamage = trueDamage;
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    /** What the attacker's action-bar chain meter currently shows. */
    private static class CountDisplay {
        int count = 0;
        int needed = 10;
        long lastHit = 0;
        long timeoutMs = 8000;
        long chainedUntil = 0;
        long chainedDuration = 0;
        String targetName = "";
        String filled = "&7";
        String empty = "&8";
    }

    public ChainManager(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.attackDamage = resolveAttribute("GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE");
    }

    /** Attributes were renamed in newer versions, so look them up by name at runtime. */
    private static Attribute resolveAttribute(String... names) {
        for (String name : names) {
            try {
                Object value = Attribute.class.getField(name).get(null);
                if (value instanceof Attribute) return (Attribute) value;
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(chained.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity living) removeRoot(living);
        }
        for (BossBar bb : linkBars.values()) bb.removeAll();
        countBars.clear();
        linkBars.clear();
        chained.clear();
        links.clear();
        hitCounts.clear();
        lastHits.clear();
        linkCooldowns.clear();
    }

    // ---------- Sword hits ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (mirroring) return; // mirrored link damage isn't a real hit
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (target.equals(attacker)) return;

        ItemStack held = attacker.getInventory().getItemInMainHand();
        Weapon weapon = manager.getWeapon(held);
        if (weapon == null || weapon.getChainConfig() == null) return;

        ConfigurationSection cfg = weapon.getChainConfig();
        ConfigurationSection linkCfg = cfg.getConfigurationSection("damage-link");
        long now = System.currentTimeMillis();

        boolean requireCrouch = linkCfg != null && linkCfg.getBoolean("require-crouch", true);
        if (requireCrouch && attacker.isSneaking()) {
            // Crouch + hit = link mode (doesn't add to the chain stack)
            tryLink(attacker, target, linkCfg, now, weapon, held);
        } else {
            countHit(attacker, target, cfg, now, event.getDamage());
            if (!requireCrouch) tryLink(attacker, target, linkCfg, now, weapon, held);
        }
    }

    /** Was this swing fully charged? Partial swings deal visibly less damage than the attacker's full damage. */
    private boolean isFullyCharged(Player attacker, double dealt) {
        if (attackDamage == null) return true; // can't tell on this version - don't block the sword
        AttributeInstance inst = attacker.getAttribute(attackDamage);
        if (inst == null) return true;
        double full = inst.getValue();
        if (full <= 0) return true;
        return dealt >= full * FULL_CHARGE_RATIO;
    }

    /** N fully charged hits on the same target chains them in place. */
    private void countHit(Player attacker, LivingEntity target, ConfigurationSection cfg, long now, double dealt) {
        if (chained.containsKey(target.getUniqueId())) return;

        if (cfg.getBoolean("require-full-charge", true) && !isFullyCharged(attacker, dealt)) {
            actionBar(attacker, "&cWait for a full swing to stack chains");
            return;
        }

        int needed = Math.max(1, cfg.getInt("hits", 10));
        long timeoutMs = (long) (cfg.getDouble("hit-timeout", 8) * 1000);

        HitCount hc = hitCounts
                .computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(target.getUniqueId(), k -> new HitCount());

        if (hc.count > 0 && now - hc.last > timeoutMs) hc.count = 0;
        hc.count++;
        hc.last = now;

        // A chain-link ring on the target for every stack
        Location mid = target.getLocation().add(0, target.getHeight() / 2.0, 0);
        drawChain(target.getWorld(), ringPath(mid, 0.5 + 0.03 * hc.count), IRON_DARK, IRON_LIGHT);
        target.getWorld().playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 0.6f + 0.08f * hc.count);

        boolean gui = cfg.getBoolean("gui", true);
        String barColor = cfg.getString("bar-color", "&7");
        String barEmpty = cfg.getString("bar-empty-color", "&8");
        if (hc.count >= needed) {
            hc.count = 0;
            double duration = cfg.getDouble("duration", 4);
            chain(attacker, target, duration, now);
            if (gui) showChainedBar(attacker, target, duration, now, barColor, barEmpty);
        } else if (gui) {
            showCountBar(attacker, target, hc.count, needed, timeoutMs, now, barColor, barEmpty);
        } else {
            actionBar(attacker, "&7Chain: &f" + hc.count + "&7/&f" + needed);
        }
    }

    private void chain(Player attacker, LivingEntity target, double seconds, long now) {
        long until = now + (long) (seconds * 1000);
        chained.put(target.getUniqueId(), until);
        applyRoot(target, until);
        bindEffect(target);

        manager.message(attacker, "chain-bound-attacker", "{target}", target.getName());
        if (target instanceof Player victim) {
            manager.message(victim, "chain-bound-victim", "{player}", attacker.getName());
        }
    }

    // ---------- Action-bar "GUI" for the chain stack ----------

    private void showCountBar(Player attacker, LivingEntity target, int count, int needed, long timeoutMs, long now,
                              String filled, String empty) {
        CountDisplay cd = countBars.computeIfAbsent(attacker.getUniqueId(), k -> new CountDisplay());
        cd.count = count;
        cd.needed = needed;
        cd.lastHit = now;
        cd.timeoutMs = timeoutMs;
        cd.chainedUntil = 0;
        cd.targetName = target.getName();
        cd.filled = filled;
        cd.empty = empty;
        renderDisplay(attacker, cd, now);
    }

    /** When the stack completes the meter switches to a draining "CHAINED" bar. */
    private void showChainedBar(Player attacker, LivingEntity target, double seconds, long now,
                                String filled, String empty) {
        CountDisplay cd = countBars.computeIfAbsent(attacker.getUniqueId(), k -> new CountDisplay());
        cd.lastHit = now;
        cd.chainedDuration = (long) (seconds * 1000);
        cd.chainedUntil = now + cd.chainedDuration;
        cd.targetName = target.getName();
        cd.filled = filled;
        cd.empty = empty;
        renderDisplay(attacker, cd, now);
    }

    /** Draws the meter. Called on every hit and re-sent every few ticks so it stays on screen. */
    private void renderDisplay(Player player, CountDisplay cd, long now) {
        if (cd.chainedUntil > 0) {
            long left = Math.max(0, cd.chainedUntil - now);
            double fraction = cd.chainedDuration > 0 ? left / (double) cd.chainedDuration : 0;
            actionBar(player, "&7&lCHAINED &f" + cd.targetName + " &8[" + drain(fraction, 20, cd.filled, cd.empty)
                    + "&8] &7" + (int) Math.ceil(left / 1000.0) + "s");
        } else {
            actionBar(player, "&7Chain &8[" + stackSegments(cd.count, cd.needed, cd.filled, cd.empty) + "&8] &7"
                    + cd.count + "&8/&7" + cd.needed + " &8- &7" + cd.targetName);
        }
    }

    /** One '|' per hit (up to 30): filled ones in the filled colour, the rest in the empty colour. */
    private static String stackSegments(int count, int needed, String filledColor, String emptyColor) {
        int segments = Math.max(1, Math.min(needed, 30));
        int filled = (int) Math.round(segments * Math.min(1.0, count / (double) needed));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            sb.append(i < filled ? filledColor : emptyColor).append('|');
        }
        return sb.toString();
    }

    private static String drain(double fraction, int segments, String filledColor, String emptyColor) {
        int filled = (int) Math.round(segments * Math.max(0, Math.min(1, fraction)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) sb.append(i < filled ? filledColor : emptyColor).append('|');
        return sb.toString();
    }

    // ---------- Crouch + hit two people: link them ----------

    private void tryLink(Player attacker, LivingEntity target, ConfigurationSection cfg, long now,
                         Weapon weapon, ItemStack held) {
        if (cfg == null) return;
        if (!(target instanceof Player) && !cfg.getBoolean("include-mobs", false)) return;

        UUID attackerId = attacker.getUniqueId();
        long windowMs = (long) (cfg.getDouble("window", 5) * 1000);
        LastHit last = lastHits.get(attackerId);

        if (last != null && !last.target.equals(target.getUniqueId()) && now - last.time <= windowMs) {
            Entity previous = Bukkit.getEntity(last.target);

            Long cooldownEnd = linkCooldowns.get(attackerId);
            if (cooldownEnd != null && now < cooldownEnd) {
                actionBar(attacker, "&cChain link recharging: &f" + (int) Math.ceil((cooldownEnd - now) / 1000.0) + "s");
                return;
            }

            if (previous instanceof LivingEntity other && other.isValid() && !other.isDead()
                    && !links.containsKey(other.getUniqueId()) && !links.containsKey(target.getUniqueId())) {

                double seconds = cfg.getDouble("duration", 15);
                long until = now + (long) (seconds * 1000);
                Link link = new Link(other.getUniqueId(), target.getUniqueId(), attackerId, now, until,
                        cfg.getDouble("share", 1.0), cfg.getBoolean("true-damage", false));
                links.put(link.a, link);
                links.put(link.b, link);

                double cooldownSeconds = cfg.getDouble("cooldown", 15);
                linkCooldowns.put(attackerId, now + (long) (cooldownSeconds * 1000));
                lastHits.remove(attackerId);

                // Minecraft's own item cooldown (grey overlay). Applies to every item of that type.
                if (weapon.usesMinecraftCooldown()) {
                    int ticks = (int) Math.round(cooldownSeconds * 20);
                    if (ticks > 0) attacker.setCooldown(held.getType(), ticks);
                }

                linkEffect(other, target);
                if (cfg.getBoolean("gui", true)) createLinkBar(link, attacker, other, target, seconds);

                manager.message(attacker, "chain-linked-attacker", "{a}", other.getName(), "{b}", target.getName());
                if (other instanceof Player p) {
                    manager.message(p, "chain-linked-victim", "{other}", target.getName());
                }
                if (target instanceof Player p) {
                    manager.message(p, "chain-linked-victim", "{other}", other.getName());
                }
                return;
            }
        }

        // First crouch-hit: mark this target and wait for a second one
        lastHits.put(attackerId, new LastHit(target.getUniqueId(), now));
        markEffect(target);
        actionBar(attacker, "&7Marked &f" + target.getName() + "&7 - crouch-hit another player to chain them");
    }

    private void createLinkBar(Link link, Player owner, LivingEntity a, LivingEntity b, double seconds) {
        BossBar bar = Bukkit.createBossBar(
                color("&c&lLINKED &f" + a.getName() + " &c<-> &f" + b.getName() + " &8- &f" + (int) Math.ceil(seconds) + "s"),
                BarColor.RED, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(owner);
        if (a instanceof Player pa && !pa.equals(owner)) bar.addPlayer(pa);
        if (b instanceof Player pb && !pb.equals(owner)) bar.addPlayer(pb);
        linkBars.put(link, bar);
    }

    // ---------- Shared damage ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (mirroring || links.isEmpty()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        Link link = links.get(victim.getUniqueId());
        if (link == null) return;
        if (event.getFinalDamage() <= 0) return; // nothing got through (fully blocked etc.)

        // The damage as dealt BEFORE the victim's armor, so the other person's own armor applies normally
        double amount = event.getDamage() * link.share;
        if (amount <= 0) return;

        Entity otherEntity = Bukkit.getEntity(link.other(victim.getUniqueId()));
        if (!(otherEntity instanceof LivingEntity other) || !other.isValid() || other.isDead()) {
            endLink(link);
            return;
        }

        Player owner = Bukkit.getPlayer(link.owner);
        mirroring = true;
        if (link.trueDamage) ActiveAbilities.TRUE_DAMAGE.add(other.getUniqueId());
        try {
            other.setNoDamageTicks(0);
            if (owner != null && owner.isOnline()) {
                other.damage(amount, owner);
            } else {
                other.damage(amount);
            }
        } finally {
            if (link.trueDamage) ActiveAbilities.TRUE_DAMAGE.remove(other.getUniqueId());
            mirroring = false;
        }

        // The damage visibly travels down the red chain
        if (victim.getWorld().equals(other.getWorld())) {
            World w = victim.getWorld();
            Location from = victim.getLocation().add(0, victim.getHeight() * 0.55, 0);
            Location to = other.getLocation().add(0, other.getHeight() * 0.55, 0);
            redLine(w, from, to);
            Fx.burst(w, from, 20, 0.25, Particle.CRIT);
            Fx.burst(w, to, 30, 0.3, Particle.CRIT);
            w.playSound(to, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 1.3f);
        }
    }

    // ---------- Keeping chained players in place ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (chained.isEmpty() || !chained.containsKey(event.getPlayer().getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location fixed = to.clone();
            fixed.setX(from.getX());
            fixed.setZ(from.getZ());
            event.setTo(fixed); // can still look around and fall, but can't walk
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!chained.containsKey(event.getPlayer().getUniqueId())) return;
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        chained.remove(id);
        Link link = links.get(id);
        if (link != null) endLink(link);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (chained.remove(id) != null) removeRoot(event.getPlayer());
        hitCounts.remove(id);
        lastHits.remove(id);
        countBars.remove(id);
        Link link = links.get(id);
        if (link != null) endLink(link);
    }

    // ---------- Periodic: visuals, boss bars, expiry ----------

    private void tick() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Long>> it = chained.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());

            if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                it.remove();
                continue;
            }
            if (now >= entry.getValue()) {
                it.remove();
                removeRoot(living);
                breakEffect(living, false);
                continue;
            }
            applyRoot(living, entry.getValue()); // keep the effect topped up (milk, etc.)
            chainVisuals(living, now);
        }

        for (Link link : new HashSet<>(links.values())) {
            Entity ea = Bukkit.getEntity(link.a);
            Entity eb = Bukkit.getEntity(link.b);
            boolean ok = ea instanceof LivingEntity && eb instanceof LivingEntity
                    && ea.isValid() && eb.isValid() && !ea.isDead() && !eb.isDead() && now < link.until;
            if (!ok) {
                endLink(link);
                continue;
            }
            linkVisuals((LivingEntity) ea, (LivingEntity) eb);

            BossBar bar = linkBars.get(link);
            if (bar != null) {
                double left = (link.until - now) / 1000.0;
                bar.setProgress(Math.max(0.0, Math.min(1.0, (link.until - now) / (double) (link.until - link.start))));
                bar.setTitle(color("&c&lLINKED &f" + ea.getName() + " &c<-> &f" + eb.getName()
                        + " &8- &f" + (int) Math.ceil(left) + "s"));
            }
        }

        // Chain stack meters: re-send so they stay on screen, drop them when the stack runs out
        Iterator<Map.Entry<UUID, CountDisplay>> bi = countBars.entrySet().iterator();
        while (bi.hasNext()) {
            Map.Entry<UUID, CountDisplay> entry = bi.next();
            CountDisplay cd = entry.getValue();
            Player p = Bukkit.getPlayer(entry.getKey());

            if (p == null || !p.isOnline()) {
                bi.remove();
                continue;
            }
            if (cd.chainedUntil > 0 ? now >= cd.chainedUntil : now - cd.lastHit > cd.timeoutMs) {
                bi.remove();
                continue;
            }
            renderDisplay(p, cd, now);
        }

        // Forget stale hit counts and old marks
        long cutoff = now - 60_000L;
        hitCounts.values().removeIf(map -> {
            map.values().removeIf(hc -> hc.last < cutoff);
            return map.isEmpty();
        });
        lastHits.values().removeIf(lh -> lh.time < cutoff);
    }

    private void endLink(Link link) {
        links.remove(link.a);
        links.remove(link.b);
        BossBar bar = linkBars.remove(link);
        if (bar != null) bar.removeAll();
        for (UUID id : new UUID[]{link.a, link.b}) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity living && living.isValid()) breakEffect(living, true);
        }
    }

    // ---------- Root (slowness) ----------

    private void applyRoot(LivingEntity entity, long untilMs) {
        PotionEffectType slow = PotionEffectType.getByKey(NamespacedKey.minecraft("slowness"));
        if (slow == null) return;
        int ticks = (int) Math.max(1, (untilMs - System.currentTimeMillis()) / 50) + 10;
        entity.addPotionEffect(new PotionEffect(slow, ticks, 255, true, false, false));
    }

    private void removeRoot(LivingEntity entity) {
        PotionEffectType slow = PotionEffectType.getByKey(NamespacedKey.minecraft("slowness"));
        if (slow != null) entity.removePotionEffect(slow);
    }

    // ---------- Visuals: chains like the Minecraft chain block ----------

    /**
     * Draws a chain along a path of points about one link apart. Like the chain block's texture, links
     * alternate between an oval seen face-on and a thin link seen edge-on.
     */
    private void drawChain(World w, List<Location> path, Color dark, Color light) {
        int n = path.size();
        for (int i = 0; i < n; i++) {
            Location c = path.get(i);
            Vector tangent = path.get(Math.min(n - 1, i + 1)).toVector().subtract(path.get(Math.max(0, i - 1)).toVector());
            if (tangent.lengthSquared() < 1e-6) continue;
            tangent.normalize();

            Vector ref = Math.abs(tangent.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
            Vector side = tangent.clone().crossProduct(ref).normalize();

            if (i % 2 == 0) {
                // Link seen face-on: a small oval, long side along the chain
                int pts = Math.max(5, Math.min(8, Fx.n(6)));
                for (int k = 0; k < pts; k++) {
                    double ang = 2 * Math.PI * k / pts;
                    Vector off = tangent.clone().multiply(Math.cos(ang) * 0.2).add(side.clone().multiply(Math.sin(ang) * 0.09));
                    Fx.dust(w, c.clone().add(off), 1, 0, dark, light, 0.8f);
                }
            } else {
                // Link seen edge-on: a thin bar
                for (int k = -1; k <= 1; k++) {
                    Fx.dust(w, c.clone().add(tangent.clone().multiply(k * 0.15)), 1, 0, light, dark, 0.8f);
                }
            }
        }
    }

    /** Points along a straight (or hanging) rope from one location to another, about one link apart. */
    private List<Location> ropePath(Location from, Location to, double sag) {
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        List<Location> pts = new ArrayList<>();
        if (len < 0.2 || len > 48) return pts;
        int count = Math.max(2, (int) Math.min(100, Math.ceil(len / 0.4)));
        for (int i = 0; i <= count; i++) {
            double t = (double) i / count;
            Location p = from.clone().add(d.clone().multiply(t));
            if (sag > 0) p.add(0, -Math.sin(Math.PI * t) * sag, 0);
            pts.add(p);
        }
        return pts;
    }

    /** A horizontal loop of chain around a point. */
    private List<Location> ringPath(Location center, double radius) {
        int count = Math.max(8, (int) Math.ceil(2 * Math.PI * radius / 0.4));
        List<Location> pts = new ArrayList<>();
        for (int i = 0; i <= count; i++) {
            double a = 2 * Math.PI * i / count;
            pts.add(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
        return pts;
    }

    /** A chain spiralling up a body from the feet. fraction (0..1) lets it grow upward. */
    private List<Location> helixPath(Location base, double h, double radius, double turns, int dir, double fraction) {
        double height = h * 0.9;
        double arc = Math.sqrt(Math.pow(2 * Math.PI * radius * turns, 2) + height * height);
        int count = Math.max(3, (int) Math.ceil(arc * fraction / 0.4));
        List<Location> pts = new ArrayList<>();
        for (int i = 0; i <= count; i++) {
            double t = fraction * i / count;
            double a = dir * turns * 2 * Math.PI * t;
            pts.add(base.clone().add(Math.cos(a) * radius, 0.1 + height * t, Math.sin(a) * radius));
        }
        return pts;
    }

    /** Little gold padlock where the chains cross on the chest. */
    private void padlock(World w, Location chest, Vector forward) {
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 0; dy++) {
                Fx.dust(w, chest.clone().add(right.clone().multiply(dx * 0.07)).add(0, dy * 0.07, 0), 1, 0,
                        GOLD_DARK, GOLD_LIGHT, 1.0f);
            }
        }
        for (int k = 0; k <= 4; k++) {
            double a = Math.PI * k / 4;
            Fx.dust(w, chest.clone().add(right.clone().multiply(Math.cos(a) * 0.07)).add(0, 0.07 + Math.sin(a) * 0.08, 0),
                    1, 0, GOLD_LIGHT, GOLD_DARK, 0.9f);
        }
    }

    /** A chained-in-place target: chains criss-crossed around the body, loops, a padlock, chains to the ground. */
    private void chainVisuals(LivingEntity e, long now) {
        World w = e.getWorld();
        Location base = e.getLocation();
        double h = Math.max(1.0, e.getHeight());
        double r = Math.max(0.4, e.getWidth() / 2.0 + 0.12);
        long t = now / 50;

        double yaw = Math.toRadians(base.getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));

        // Two chains spiralling opposite ways make an X across the body
        drawChain(w, helixPath(base, h, r, 2.0, +1, 1.0), IRON_DARK, IRON_LIGHT);
        drawChain(w, helixPath(base, h, r, 2.0, -1, 1.0), IRON_DARK, IRON_LIGHT);

        // Loops around the legs, waist and chest
        double[] loops = {0.3, h * 0.5, h * 0.78};
        for (double y : loops) {
            drawChain(w, ringPath(base.clone().add(0, y, 0), r + 0.05), IRON_DARK, IRON_LIGHT);
        }

        padlock(w, base.clone().add(forward.clone().multiply(r + 0.08)).add(0, h * 0.62, 0), forward);

        // Taut chains from the body to the ground
        int anchors = 4;
        for (int i = 0; i < anchors; i++) {
            double angle = i * 2 * Math.PI / anchors + Math.PI / 4;
            Location body = base.clone().add(Math.cos(angle) * r, h * 0.5, Math.sin(angle) * r);
            Location anchor = base.clone().add(Math.cos(angle) * 2.0, 0.05, Math.sin(angle) * 2.0);
            drawChain(w, ropePath(body, anchor, 0), IRON_DARK, IRON_LIGHT);
            Fx.spawn(w, Particle.CRIT, anchor, 2, 0.1, 0.05, 0.1, 0.05);
        }

        // A little ash and the odd spark
        Location mid = base.clone().add(0, h / 2.0, 0);
        Fx.spawn(w, Particle.ASH, mid, Fx.n(4), 0.45, 0.7, 0.45, 0.01);
        if (t % 10 < 5) Fx.spawn(w, Particle.CRIT, mid, 3, 0.4, 0.5, 0.4, 0.1);
    }

    /** A red chain hanging between the two linked targets, with red loops around their waists. */
    private void linkVisuals(LivingEntity a, LivingEntity b) {
        if (!a.getWorld().equals(b.getWorld())) return;
        World w = a.getWorld();
        Location from = a.getLocation().add(0, a.getHeight() * 0.5, 0);
        Location to = b.getLocation().add(0, b.getHeight() * 0.5, 0);

        double sag = Math.min(1.2, from.distance(to) * 0.12);
        drawChain(w, ropePath(from, to, sag), RED_DARK, RED_LIGHT);

        drawChain(w, ringPath(from, Math.max(0.4, a.getWidth() / 2.0 + 0.12)), RED_DARK, RED_LIGHT);
        drawChain(w, ringPath(to, Math.max(0.4, b.getWidth() / 2.0 + 0.12)), RED_DARK, RED_LIGHT);
    }

    /** A flash of red along the link when damage is shared. */
    private void redLine(World w, Location from, Location to) {
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.normalize();
        double step = Math.max(0.35, len / 100.0);
        for (double x = 0; x <= len; x += step) {
            Fx.dust(w, from.clone().add(d.clone().multiply(x)), 1, 0, RED_LIGHT, Color.WHITE, 1.4f);
        }
    }

    /** First crouch-hit: a red ring marks the target while you pick the second one. */
    private void markEffect(LivingEntity e) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        drawChain(w, ringPath(mid, 0.7), RED_DARK, RED_LIGHT);
        Fx.burst(w, mid, 15, 0.2, Particle.CRIT);
        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 1.1f);
    }

    /** The moment of binding: a flash, a shock of grey dust, chains winding up the body and whipping to the ground. */
    private void bindEffect(LivingEntity e) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        Location ground = e.getLocation().add(0, 0.15, 0);

        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.dust(w, mid, Fx.n(60), 0.9, IRON_DARK, IRON_LIGHT, 1.6f);
        Fx.burst(w, mid, 60, 0.35, Particle.CRIT);
        Fx.burst(w, mid, 40, 0.2, Particle.ASH);
        dustShockwave(w, ground, 4.0, 8, IRON_DARK, IRON_LIGHT, 2f);

        final int steps = 8;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double f = (double) t / steps;
                Location base = e.getLocation();
                double h = Math.max(1.0, e.getHeight());
                double r = Math.max(0.4, e.getWidth() / 2.0 + 0.12);

                // Chains wind up the body from the feet
                drawChain(w, helixPath(base, h, r, 2.0, +1, f), IRON_DARK, IRON_LIGHT);
                drawChain(w, helixPath(base, h, r, 2.0, -1, f), IRON_DARK, IRON_LIGHT);

                // ...and shoot out to the ground
                for (int i = 0; i < 4; i++) {
                    double angle = i * 2 * Math.PI / 4 + Math.PI / 4;
                    Location body = base.clone().add(Math.cos(angle) * r, h * 0.5, Math.sin(angle) * r);
                    Location anchor = base.clone().add(Math.cos(angle) * 2.0, 0.05, Math.sin(angle) * 2.0);
                    Location tip = body.clone().add(anchor.toVector().subtract(body.toVector()).multiply(f));
                    drawChain(w, ropePath(body, tip, 0), IRON_DARK, IRON_LIGHT);
                    if (t >= steps) Fx.burst(w, anchor, 8, 0.15, Particle.CRIT);
                }
                if (t >= steps) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 0.7f);
        w.playSound(mid, Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.8f);
        w.playSound(mid, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8f, 0.6f);
    }

    /** Two targets get linked: red loops appear on both and a red chain snaps across between them. */
    private void linkEffect(LivingEntity a, LivingEntity b) {
        for (LivingEntity e : new LivingEntity[]{a, b}) {
            World w = e.getWorld();
            Location mid = e.getLocation().add(0, e.getHeight() * 0.5, 0);
            Fx.spawn(w, Particle.FLASH, mid, 1);
            Fx.burst(w, mid, 40, 0.3, Particle.CRIT);
            Fx.dust(w, mid, Fx.n(40), 0.6, RED_DARK, RED_LIGHT, 1.6f);
            drawChain(w, ringPath(mid, Math.max(0.4, e.getWidth() / 2.0 + 0.12)), RED_DARK, RED_LIGHT);
            w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 1.0f);
        }
        if (!a.getWorld().equals(b.getWorld())) return;

        World w = a.getWorld();
        final int steps = 5;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double f = (double) t / steps;
                Location from = a.getLocation().add(0, a.getHeight() * 0.5, 0);
                Location to = b.getLocation().add(0, b.getHeight() * 0.5, 0);
                Location tip = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f));
                drawChain(w, ropePath(from, tip, Math.min(1.2, from.distance(to) * 0.12) * f), RED_DARK, RED_LIGHT);
                if (t >= steps) {
                    w.playSound(to, Sound.BLOCK_ANVIL_PLACE, 0.7f, 1.2f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** The chains shatter into dust and ash with a metallic clang (red for a link). */
    private void breakEffect(LivingEntity e, boolean red) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        Color dark = red ? RED_DARK : IRON_DARK;
        Color light = red ? RED_LIGHT : IRON_LIGHT;
        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.dust(w, mid, Fx.n(70), 0.8, light, dark, 1.6f);
        Fx.burst(w, mid, 60, 0.4, Particle.CRIT);
        Fx.burst(w, mid, 50, 0.25, Particle.ASH);
        dustShockwave(w, e.getLocation().add(0, 0.2, 0), 3.5, 6, light, dark, 1.8f);
        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.2f, 1.5f);
        w.playSound(mid, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.4f);
    }

    /** Dust ring that expands over several ticks. */
    private void dustShockwave(World w, Location c, double maxRadius, int ticks, Color from, Color to, float size) {
        final Location center = c.clone();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double r = maxRadius * t / ticks;
                int pts = Math.min(160, Fx.n((int) Math.max(14, r * 10)));
                Fx.ringDust(w, center, r, pts, from, to, size);
                if (t >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(text)));
    }
}
