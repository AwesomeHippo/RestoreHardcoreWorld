package com.awesomehippo.restorehardcoreworld;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClientEvents {
    private static final int BUTTON_DELAY = 20; // 20 ticks, same as the death screen

    private int enableIn;
    private Button restoreButton;
    private boolean captureDrops;
    private ListTag savedInventory;
    private int savedLevel;
    private int savedTotal;
    private float savedProgress;
    private final Map<UUID, ChunkPos> drops = new HashMap<>();
    private ResourceKey<Level> deathDim;
    private volatile boolean restoreDone;
    private volatile boolean restoreOk;
    private int quitIn;
    private boolean restoreBusy;
    private boolean keepHardcoreChoice;
    private Path levelDatPath;

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        // singleplayer only
        if (!(screen instanceof DeathScreen) || !isHardcoreWorld() || !Minecraft.getInstance().hasSingleplayerServer()) {
            return;
        }
        // restore world button on death
        this.restoreButton = Button.builder(Component.literal("Restore World"), (button) -> this.restoreHardcoreWorld()).bounds(screen.width / 2 - 100, screen.height / 4 + 120, 200, 20).build();
        this.restoreButton.active = false;
        this.enableIn = BUTTON_DELAY;
        event.addListener(this.restoreButton);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (this.restoreDone) {
            this.restoreDone = false;
            // wait one tick so the respawn packet is applied
            this.quitIn = 1;
            return;
        }
        if (this.quitIn > 0) {
            this.quitIn--;
            if (this.quitIn == 0) {
                this.finishRestore(mc);
            }
            return;
        }
        if (this.enableIn > 0 && mc.screen instanceof DeathScreen) {
            this.enableIn--;
            if (this.enableIn == 0 && this.restoreButton != null && !this.restoreBusy) {
                this.restoreButton.active = true;
            }
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSingleplayer() || !server.isHardcore() || !server.isSingleplayerOwner(player.getGameProfile())) {
            return;
        }
        this.captureDrops = true;
        this.drops.clear();
        this.savedInventory = player.getInventory().save(new ListTag());
        this.savedLevel = player.experienceLevel;
        this.savedTotal = player.totalExperience;
        this.savedProgress = player.experienceProgress;
        this.deathDim = player.level().dimension();
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!this.captureDrops || event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ExperienceOrb orb) {
            this.drops.put(orb.getUUID(), new ChunkPos(orb.blockPosition()));
        }
    }

    @SubscribeEvent
    public void onDrops(LivingDropsEvent event) {
        if (!this.captureDrops || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        for (ItemEntity item : event.getDrops()) {
            this.drops.put(item.getUUID(), new ChunkPos(item.blockPosition()));
        }
        this.captureDrops = false;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !this.captureDrops) {
            return;
        }
        this.captureDrops = false;
        this.drops.clear();
        this.savedInventory = null;
    }

    private boolean isHardcoreWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getLevelData().isHardcore();
    }

    private void restoreHardcoreWorld() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.level == null) { // the button isn't shown already anyway
            return;
        }
        if (this.restoreBusy) {
            return;
        }
        this.restoreBusy = true;
        if (this.restoreButton != null) {
            this.restoreButton.active = false;
        }
        this.keepHardcoreChoice = Config.KEEP_HARDCORE.get();
        this.levelDatPath = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        this.restoreOk = false;
        boolean keepInventory = Config.KEEP_INVENTORY.get();
        server.execute(() -> this.prepareRestore(server, keepInventory));
    }

    private void prepareRestore(IntegratedServer server, boolean keepInventory) {
        try {
            server.saveEverything(true, false, false);
            this.backupLevel(server);
            this.restoreOk = this.applyRestore(server, keepInventory);
        } catch (Exception ignored) {
            this.restoreOk = false;
        }
        this.restoreDone = true;
    }

    private void finishRestore(Minecraft mc) {
        if (!this.restoreOk) {
            this.restoreBusy = false;
            this.enableIn = 0;
            if (this.restoreButton != null) {
                this.restoreButton.active = true;
            }
            mc.getToasts().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_ACCESS_FAILURE, Component.literal("Restore failed"), null));
            return;
        }
        this.restoreBusy = false;
        this.enableIn = 0;
        this.restoreButton = null;
        String worldName = "";
        if (mc.getSingleplayerServer() != null) {
            worldName = mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        if (!this.keepHardcoreChoice) {
            try {
                this.patchHardcore(this.levelDatPath);
            } catch (IOException e) {
                mc.setScreen(new SelectWorldScreen(new TitleScreen()));
                mc.getToasts().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_ACCESS_FAILURE, Component.literal("Hardcore stayed on"), null));
                return;
            }
        }
        mc.setScreen(new SelectWorldScreen(new TitleScreen()));
        // nice way to notify
        mc.getToasts().addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.WORLD_BACKUP, Component.literal("Success"), Component.literal("Hardcore world '" + worldName + "' restored! You can now rejoin the world")));
    }

    private void backupLevel(IntegratedServer server) throws IOException {
        Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        Path parent = levelDat.getParent();
        if (parent == null || !Files.exists(levelDat)) { // would be very odd anyway
            throw new IOException("level.dat");
        }
        // backup just in case..
        Files.copy(levelDat, parent.resolve("level.dat_backup_restorehardcoreworld"), StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean applyRestore(IntegratedServer server, boolean keepInventory) {
        ServerPlayer player = null;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (server.isSingleplayerOwner(online.getGameProfile())) {
                player = online;
                break;
            }
        }
        if (player == null) {
            return false;
        }
        if (!keepInventory) {
            // XPs are part of the keepinv
            player.experienceLevel = 0;
            player.experienceProgress = 0.0F;
            player.totalExperience = 0;
            // clear/drop inv if no keepinventory
            player.getInventory().dropAll();
        }
        ServerPlayer target = player;
        if (player.getHealth() <= 0.0F) {
            target = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            player.connection.player = target;
        }
        if (keepInventory && this.savedInventory != null) {
            this.discardDrops(server);
            target.getInventory().load(this.savedInventory);
            if (!target.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
                this.stripVanishing(target);
            }
            target.experienceLevel = this.savedLevel;
            target.totalExperience = this.savedTotal;
            target.experienceProgress = this.savedProgress;
        }
        this.savedInventory = null;
        this.drops.clear();

        target.stopRiding();
        if (target.isSleeping()) {
            target.stopSleeping();
        }
        // reset death related stats
        // entity data available here: https://minecraft.wiki/w/Player.dat_format
        target.setAbsorptionAmount(0.0F);
        target.setHealth(target.getMaxHealth());
        target.deathTime = 0;
        target.hurtTime = 0;
        target.clearFire();
        target.setTicksFrozen(0);
        target.resetFallDistance();
        target.setAirSupply(target.getMaxAirSupply());
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(5.0F);
        target.getFoodData().setExhaustion(0.0F);
        // remove all the effects (potions etc...) of the player
        target.removeAllEffects();
        // reset motion (stationary)
        target.setDeltaMovement(Vec3.ZERO);
        target.setPose(Pose.STANDING);
        // put survival again
        if (target.isSpectator()) {
            server.getGameRules().getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(true, server);
        }
        target.setGameMode(GameType.SURVIVAL);
        return true;
    }

    private void discardDrops(IntegratedServer server) {
        if (this.deathDim == null || this.drops.isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(this.deathDim);
        if (level == null) {
            return;
        }
        for (Map.Entry<UUID, ChunkPos> drop : this.drops.entrySet()) {
            Entity entity = level.getEntity(drop.getKey());
            if (entity == null) {
                level.getChunk(drop.getValue().x, drop.getValue().z);
                entity = level.getEntity(drop.getKey());
            }
            if (entity != null) {
                entity.discard();
            }
        }
    }

    private void stripVanishing(ServerPlayer player) {
        Holder<Enchantment> vanishing = player.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.VANISHING_CURSE);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (EnchantmentHelper.getItemEnchantmentLevel(vanishing, stack) > 0) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    // modify the level.dat
    private void patchHardcore(Path levelDat) throws IOException {
        if (levelDat == null || !Files.exists(levelDat)) {
            throw new IOException("level.dat");
        }
        // read/modify the NBT
        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        if (!root.contains("Data", 10)) {
            throw new IOException("level.dat");
        }
        CompoundTag data = root.getCompound("Data");
        data.putBoolean("hardcore", false);
        data.putBoolean("DifficultyLocked", false);
        if (data.contains("Player", 10)) {
            data.getCompound("Player").putInt("playerGameType", GameType.SURVIVAL.getId());
        }
        Path parent = levelDat.getParent();
        if (parent == null) {
            throw new IOException("level.dat");
        }
        Path tmp = Files.createTempFile(parent, "level", ".dat");
        try {
            NbtIo.writeCompressed(root, tmp);
            Util.safeReplaceFile(levelDat, tmp, parent.resolve("level.dat_old"));
            if (NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).getCompound("Data").getBoolean("hardcore")) {
                throw new IOException("level.dat");
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
