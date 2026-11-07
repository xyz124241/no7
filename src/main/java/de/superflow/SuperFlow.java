package de.superflow;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.BrewingStand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SuperFlow extends JavaPlugin implements Listener, TabExecutor {

    private final Set<UUID> frozenVillagers = new HashSet<>();

    private boolean unlimitedTrades, freezeVillager;
    private boolean xpMerge;
    private double xpRadius;
    private boolean instantBrew, freeFuel;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var c = getConfig();
        unlimitedTrades = c.getBoolean("villager.unlimited-trades", true);
        freezeVillager = c.getBoolean("villager.freeze-while-trading", true);
        xpMerge = c.getBoolean("xp.merge", true);
        xpRadius = c.getDouble("xp.radius", 6.0);
        instantBrew = c.getBoolean("brewing.instant", true);
        freeFuel = c.getBoolean("brewing.free-fuel", true);

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("vjob")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (World w : getServer().getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (frozenVillagers.contains(v.getUniqueId())) v.setAI(true);
            }
        }
        frozenVillagers.clear();
    }

    // ------------------------------------------------------------------
    // XP-Orbs zu grossen Klumpen
    // ------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onOrbSpawn(EntitySpawnEvent e) {
        if (!xpMerge || !(e.getEntity() instanceof ExperienceOrb orb)) return;
        for (ExperienceOrb other : e.getLocation().getNearbyEntitiesByType(ExperienceOrb.class, xpRadius)) {
            if (other == orb || !other.isValid()) continue;
            other.setExperience(other.getExperience() + orb.getExperience());
            e.setCancelled(true);
            return;
        }
    }

    // ------------------------------------------------------------------
    // Villager: unendlich traden, Menue bleibt offen (Villager steht still)
    // ------------------------------------------------------------------
    private void unlock(Villager v) {
        for (int i = 0; i < v.getRecipeCount(); i++) {
            MerchantRecipe r = v.getRecipe(i);
            r.setMaxUses(Integer.MAX_VALUE / 2);
            r.setUses(0);
            r.setDemand(0);
            r.setSpecialPrice(0);
            v.setRecipe(i, r);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantOpen(InventoryOpenEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi) || !(mi.getMerchant() instanceof Villager v)) return;
        if (unlimitedTrades) unlock(v);
        if (freezeVillager && v.hasAI()) {
            v.setAI(false);
            frozenVillagers.add(v.getUniqueId());
        }
    }

    @EventHandler
    public void onMerchantClose(InventoryCloseEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi) || !(mi.getMerchant() instanceof Villager v)) return;
        if (frozenVillagers.remove(v.getUniqueId())) v.setAI(true);
    }

    @EventHandler
    public void onTrade(PlayerTradeEvent e) {
        if (!unlimitedTrades || !(e.getVillager() instanceof Villager v)) return;
        getServer().getScheduler().runTask(this, () -> { if (v.isValid()) unlock(v); });
    }

    // /vjob reroll | /vjob <beruf>
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Nur Spieler."); return true; }
        if (args.length != 1) { p.sendMessage("§e/vjob <reroll|beruf>"); return true; }
        Entity target = p.getTargetEntity(6);
        if (!(target instanceof Villager v)) { p.sendMessage("§cSchau einen Villager an."); return true; }

        Profession prof = v.getProfession();
        if (!args[0].equalsIgnoreCase("reroll")) {
            Profession wanted = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(args[0].toLowerCase()));
            if (wanted == null) { p.sendMessage("§cUnbekannter Beruf."); return true; }
            prof = wanted;
        }
        v.setProfession(prof);
        v.setVillagerLevel(1);
        v.setVillagerExperience(0);
        v.resetOffers();
        v.getRecipes();
        if (unlimitedTrades) unlock(v);
        p.sendMessage("§aVillager ist jetzt: " + prof.key().value() + " (neue Trades)");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("reroll"));
        Registry.VILLAGER_PROFESSION.forEach(pr -> out.add(pr.key().value()));
        out.removeIf(x -> !x.startsWith(a[0].toLowerCase()));
        return out;
    }

    // ------------------------------------------------------------------
    // Braustaender: Traenke sofort fertig
    // ------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onBrewClick(InventoryClickEvent e) {
        if (instantBrew && e.getView().getTopInventory() instanceof BrewerInventory bi) scheduleBrew(bi);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBrewDrag(InventoryDragEvent e) {
        if (instantBrew && e.getView().getTopInventory() instanceof BrewerInventory bi) scheduleBrew(bi);
    }

    private void scheduleBrew(BrewerInventory bi) {
        Location loc = bi.getLocation();
        if (loc == null) return;
        // Der Braustaender startet erst im naechsten Tick -> mehrmals pruefen
        for (int delay : new int[]{1, 2, 4, 8}) {
            getServer().getScheduler().runTaskLater(this, () -> speedUp(loc), delay);
        }
    }

    private void speedUp(Location loc) {
        if (!(loc.getBlock().getState() instanceof BrewingStand st)) return;
        boolean changed = false;
        if (freeFuel && st.getFuelLevel() < 20) {
            st.setFuelLevel(20);
            changed = true;
        }
        if (st.getBrewingTime() > 1) {
            st.setBrewingTime(1);
            changed = true;
        }
        if (changed) st.update();
    }
}
