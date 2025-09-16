package com.awesomehippo.restorehardcoreworld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ClientEvents {
    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        // singleplayer only
        if (screen instanceof DeathScreen && isHardcoreWorld() && Minecraft.getInstance().hasSingleplayerServer()) {
            // restore world button on death
            Button restoreButton = Button.builder(
                    Component.literal("Restore World"),
                    (button) -> restoreHardcoreWorld()
            ).bounds(screen.width / 2 - 100, screen.height / 4 + 120, 200, 20).build();
            event.addListener(restoreButton);
        }
    }

    private boolean isHardcoreWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getLevelData().isHardcore();
    }

    private void restoreHardcoreWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.hasSingleplayerServer()) { // the button isn't shown already anyway
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§cYou can only restore hard core world in singleplayer"));
            }
            return;
        }

        boolean keepHardcore = Config.KEEP_HARDCORE.get();
        boolean keepInventory = Config.KEEP_INVENTORY.get();

        // getting the level.dat
        Path levelDatPath = mc.getSingleplayerServer().getWorldPath(LevelResource.LEVEL_DATA_FILE);
        if (!Files.exists(levelDatPath)) { // would be very odd anyway
            return;
        }
        if (mc.getSingleplayerServer() != null) {
            mc.getSingleplayerServer().halt(false); // don't force save
        }
        mc.clearLevel();

        // modify the level.dat
        try {
            // backup just in case..
            Path backupPath = levelDatPath.getParent().resolve("level.dat_backup_restorehardcoreworld");
            Files.copy(levelDatPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

            // read/modify the NBT
            CompoundTag nbt = NbtIo.readCompressed(Files.newInputStream(levelDatPath));
            CompoundTag data = nbt.getCompound("Data");
            CompoundTag player = data.getCompound("Player");

            // reset death related stats
            // entity data available here: https://minecraft.wiki/w/Player.dat_format
            player.putFloat("Health", 20.0F);
            player.putInt("foodLevel", 20);
            player.putFloat("foodSaturationLevel", 5.0F);
            player.putFloat("foodExhaustionLevel", 0.0F);
            player.putShort("DeathTime", (short) 0);
            player.putShort("HurtTime", (short) 0);
            player.putInt("HurtByTimestamp", 0);

            // remove all the effects (potions etc...) of the player
            player.put("ActiveEffects", new ListTag());

            // XPs are part of keep inventory
            if (!keepInventory) {
                player.putInt("XpLevel", 0);
                player.putFloat("XpP", 0.0F);
                player.putInt("XpTotal", 0);
                player.putInt("XpSeed", 0);
            }

            // clear/drop inv if no keepinventory
            if (!keepInventory) {
                player.put("Inventory", new ListTag());
            }

            // convert to normal if keepHardcore is false
            if (!keepHardcore) {
                data.putBoolean("hardcore", false);
                data.putBoolean("DifficultyLocked", false); // unlock the difficulty if we get back to normal
                player.putInt("playerGameType", 0); // put survival again
            }

            // respawn the player correctly
            double spawnX, spawnY, spawnZ;
            if (player.contains("SpawnX") && player.contains("SpawnY") && player.contains("SpawnZ")) {
                // player's spawn
                spawnX = player.getInt("SpawnX");
                spawnY = player.getInt("SpawnY");
                spawnZ = player.getInt("SpawnZ");
            } else {
                // default world's spawn
                spawnX = data.getInt("SpawnX");
                spawnY = data.getInt("SpawnY");
                spawnZ = data.getInt("SpawnZ");
            }
            // set the dimension / position
            player.putString("Dimension", "minecraft:overworld");
            // TODO: actually support a special world spawn in a custom dimension(?)
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(spawnX));
            pos.add(DoubleTag.valueOf(spawnY));
            pos.add(DoubleTag.valueOf(spawnZ));
            player.put("Pos", pos);

            // not sure if Rotation should be reset too

            NbtIo.writeCompressed(nbt, Files.newOutputStream(levelDatPath));
            mc.setScreen(new SelectWorldScreen(new TitleScreen()));
            // nice way to notify
            String worldName = nbt.getCompound("Data").getString("LevelName");
            mc.getToasts().addToast(SystemToast.multiline(mc, SystemToast.SystemToastIds.WORLD_BACKUP, Component.literal("Success"), Component.literal("Hardcore world '" + worldName + "' restored! You can now rejoin the world")));
        } catch (IOException e) { // would be odd
            mc.setScreen(new SelectWorldScreen(new TitleScreen()));
            mc.getToasts().addToast(SystemToast.multiline(mc, SystemToast.SystemToastIds.WORLD_ACCESS_FAILURE, Component.literal("Error"), Component.literal("Failed to restore world: " + e.getMessage())));
        }
    }
}