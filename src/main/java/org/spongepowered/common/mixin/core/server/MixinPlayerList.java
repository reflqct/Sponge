/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.mixin.core.server;

import com.flowpowered.math.vector.Vector3d;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.advancements.PlayerAdvancements;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraft.network.play.server.SPacketDisconnect;
import net.minecraft.network.play.server.SPacketEntityEffect;
import net.minecraft.network.play.server.SPacketEntityStatus;
import net.minecraft.network.play.server.SPacketHeldItemChange;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketPlayerAbilities;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketRespawn;
import net.minecraft.network.play.server.SPacketServerDifficulty;
import net.minecraft.network.play.server.SPacketSetExperience;
import net.minecraft.network.play.server.SPacketSpawnPosition;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.DemoPlayerInteractionManager;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.server.management.UserListBans;
import net.minecraft.server.management.UserListIPBans;
import net.minecraft.server.management.UserListWhitelist;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.WorldServer;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.WorldInfo;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.Transform;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.CauseStackManager;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.entity.MoveEntityEvent;
import org.spongepowered.api.event.entity.living.humanoid.player.RespawnPlayerEvent;
import org.spongepowered.api.event.message.MessageEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.network.RemoteConnection;
import org.spongepowered.api.resourcepack.ResourcePack;
import org.spongepowered.api.service.ban.BanService;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.whitelist.WhitelistService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.util.annotation.NonnullByDefault;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.gamerule.DefaultGameRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.SpongeImplHooks;
import org.spongepowered.common.entity.EntityUtil;
import org.spongepowered.common.entity.player.SpongeUser;
import org.spongepowered.common.event.SpongeCommonEventFactory;
import org.spongepowered.common.event.tracking.context.GeneralizedContext;
import org.spongepowered.common.event.tracking.phase.player.PlayerPhase;
import org.spongepowered.common.interfaces.IMixinPlayerList;
import org.spongepowered.common.interfaces.IMixinServerScoreboard;
import org.spongepowered.common.interfaces.advancement.IMixinPlayerAdvancements;
import org.spongepowered.common.bridge.entity.EntityBridge;
import org.spongepowered.common.interfaces.entity.player.IMixinEntityPlayerMP;
import org.spongepowered.common.bridge.packet.WorldBorderPacketBridge;
import org.spongepowered.common.interfaces.world.IMixinITeleporter;
import org.spongepowered.common.interfaces.world.ServerWorldBridge;
import org.spongepowered.common.service.ban.SpongeIPBanList;
import org.spongepowered.common.service.ban.SpongeUserListBans;
import org.spongepowered.common.service.permission.SpongePermissionService;
import org.spongepowered.common.service.whitelist.SpongeUserListWhitelist;
import org.spongepowered.common.text.SpongeTexts;
import org.spongepowered.common.text.chat.ChatUtil;
import org.spongepowered.common.world.WorldManager;
import org.spongepowered.common.world.storage.SpongePlayerDataHandler;

import java.io.File;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

@NonnullByDefault
@Mixin(PlayerList.class)
public abstract class MixinPlayerList implements IMixinPlayerList {

    private static final String WRITE_PLAYER_DATA =
            "Lnet/minecraft/world/storage/IPlayerFileData;writePlayerData(Lnet/minecraft/entity/player/EntityPlayer;)V";
    private static final String
            SERVER_SEND_PACKET_TO_ALL_PLAYERS =
            "Lnet/minecraft/server/management/PlayerList;sendPacketToAllPlayers(Lnet/minecraft/network/Packet;)V";
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Map<UUID, EntityPlayerMP> uuidToPlayerMap;
    @Shadow @Final public List<EntityPlayerMP> playerEntityList;
    @Shadow @Final private Map<UUID, PlayerAdvancements> advancements;
    @Shadow private IPlayerFileData playerDataManager;
    @Shadow public abstract NBTTagCompound readPlayerDataFromFile(EntityPlayerMP playerIn);
    @Shadow public abstract MinecraftServer getServerInstance();
    @Shadow public abstract int getMaxPlayers();
    @Shadow public abstract void sendPacketToAllPlayers(Packet<?> packetIn);
    @Shadow public abstract void preparePlayer(EntityPlayerMP playerIn, @Nullable WorldServer worldIn);
    @Shadow public abstract void playerLoggedIn(EntityPlayerMP playerIn);
    @Shadow public abstract void updateTimeAndWeatherForPlayer(EntityPlayerMP playerIn, WorldServer worldIn);
    @Shadow public abstract void updatePermissionLevel(EntityPlayerMP p_187243_1_);
    @Shadow public abstract void syncPlayerInventory(EntityPlayerMP playerIn);
    @Nullable @Shadow public abstract String allowUserToConnect(SocketAddress address, GameProfile profile);
    @Shadow private void setPlayerGameTypeBasedOnOther(EntityPlayerMP playerIn, @Nullable EntityPlayerMP other, net.minecraft.world.World worldIn) {
        // Shadowed
    }

    /**
     * @author Minecrell - December 5th, 2016
     * @reason Redirect ban list constructor and use our custom implementation
     *     instead. Redirects all methods to the {@link BanService}.
     */
    @Redirect(method = "<init>", at = @At(value = "NEW", args = "class=net/minecraft/server/management/UserListBans"))
    private UserListBans createBanList(File file) {
        return new SpongeUserListBans(file);
    }

    /**
     * @author Minecrell - December 5th, 2016
     * @reason Redirect IP ban list constructor and use our custom implementation
     *     instead. Redirects all methods to the {@link BanService}.
     */
    @Redirect(method = "<init>", at = @At(value = "NEW", args = "class=net/minecraft/server/management/UserListIPBans"))
    private UserListIPBans createIPBanList(File file) {
        return new SpongeIPBanList(file);
    }

    /**
     * @author Minecrell - December 4th, 2016
     * @reason Redirect whitelist constructor and use our custom implementation
     *     instead. Redirects all methods to the {@link WhitelistService}.
     */
    @Redirect(method = "<init>", at = @At(value = "NEW", args = "class=net/minecraft/server/management/UserListWhitelist"))
    private UserListWhitelist createWhitelist(File file) {
        return new SpongeUserListWhitelist(file);
    }

    /**
     * Bridge methods to proxy modified method in Vanilla, nothing in Forge
     */
    public void func_72355_a(NetworkManager netManager, EntityPlayerMP playerIn) {
        this.initializeConnectionToPlayer(netManager, playerIn, null);
    }

    /**
     * Bridge methods to proxy modified method in Vanilla, nothing in Forge
     */
    public void initializeConnectionToPlayer(NetworkManager netManager, EntityPlayerMP playerIn) {
        initializeConnectionToPlayer(netManager, playerIn, null);
    }

    private void disconnectClient(NetworkManager netManager, Optional<Text> disconnectMessage, @Nullable GameProfile profile) {
        ITextComponent reason;
        if (disconnectMessage.isPresent()) {
            reason = SpongeTexts.toComponent(disconnectMessage.get());
        } else {
            reason = new TextComponentTranslation("disconnect.disconnected");
        }

        try {
            LOGGER.info("Disconnecting " + (profile != null ? profile.toString() + " (" + netManager.getRemoteAddress().toString() + ")" : String.valueOf(netManager.getRemoteAddress() + ": " + reason.getUnformattedText())));
            netManager.sendPacket(new SPacketDisconnect(reason));
            netManager.closeChannel(reason);
        } catch (Exception exception) {
            LOGGER.error("Error whilst disconnecting player", exception);
        }
    }

    private void initializeConnectionToPlayer(NetworkManager netManager, EntityPlayerMP playerIn, @Nullable NetHandlerPlayServer handler) {
        GameProfile gameprofile = playerIn.getGameProfile();
        PlayerProfileCache playerprofilecache = this.server.getPlayerProfileCache();
        GameProfile gameprofile1 = playerprofilecache.getProfileByUUID(gameprofile.getId());
        String s = gameprofile1 == null ? gameprofile.getName() : gameprofile1.getName();
        playerprofilecache.addEntry(gameprofile);

        // Sponge start - save changes to offline User before reading player data
        SpongeUser user = (SpongeUser) ((IMixinEntityPlayerMP) playerIn).getUserObject();
        if (SpongeUser.dirtyUsers.contains(user)) {
            user.save();
        }
        // Sponge end

        NBTTagCompound nbttagcompound = this.readPlayerDataFromFile(playerIn);
        WorldServer worldServer = this.server.getWorld(playerIn.dimension);
        int actualDimensionId = ((ServerWorldBridge) worldServer).getDimensionId();
        BlockPos spawnPos;
        // Join data
        Optional<Instant> firstJoined = SpongePlayerDataHandler.getFirstJoined(playerIn.getUniqueID());
        Instant lastJoined = Instant.now();
        SpongePlayerDataHandler.setPlayerInfo(playerIn.getUniqueID(), firstJoined.orElse(lastJoined), lastJoined);

        if (actualDimensionId != playerIn.dimension) {
            SpongeImpl.getLogger().warn("Player [{}] has attempted to login to unloaded world [{}]. This is not safe so we have moved them to "
                    + "the default world's spawn point.", playerIn.getName(), playerIn.dimension);
            if (!firstJoined.isPresent()) {
                spawnPos = SpongeImplHooks.getRandomizedSpawnPoint(worldServer);
            } else {
                spawnPos = worldServer.getSpawnPoint();
            }
            playerIn.dimension = actualDimensionId;
            playerIn.setPosition(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
        }

        // Sponge start - fire login event
        @Nullable String kickReason = this.allowUserToConnect(netManager.getRemoteAddress(), gameprofile);
        Text disconnectMessage;
        if (kickReason != null) {
            disconnectMessage = SpongeTexts.fromLegacy(kickReason);
        } else {
            disconnectMessage = Text.of("You are not allowed to log in to this server.");
        }

        Player player = (Player) playerIn;
        Transform<World> fromTransform = player.getTransform().setExtent((World) worldServer);

        final ClientConnectionEvent.Login loginEvent;
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.pushCause(user);
            loginEvent = SpongeEventFactory.createClientConnectionEventLogin(
                frame.getCurrentCause(), fromTransform, fromTransform, (RemoteConnection) netManager,
                new MessageEvent.MessageFormatter(disconnectMessage), (org.spongepowered.api.profile.GameProfile) gameprofile, player, false
            );

            if (kickReason != null) {
                loginEvent.setCancelled(true);
            }

            if (SpongeImpl.postEvent(loginEvent)) {
                Sponge.getCauseStackManager().popCause();
                final Optional<Text> message = loginEvent.isMessageCancelled() ? Optional.empty() : Optional.of(loginEvent.getMessage());
                this.disconnectClient(netManager, message, gameprofile);
                return;
            }
        }

        // Sponge end

        worldServer = (WorldServer) loginEvent.getToTransform().getExtent();
        double x = loginEvent.getToTransform().getPosition().getX();
        double y = loginEvent.getToTransform().getPosition().getY();
        double z = loginEvent.getToTransform().getPosition().getZ();
        float pitch = (float) loginEvent.getToTransform().getPitch();
        float yaw = (float) loginEvent.getToTransform().getYaw();

        playerIn.dimension = ((ServerWorldBridge) worldServer).getDimensionId();
        playerIn.setWorld(worldServer);
        playerIn.interactionManager.setWorld((WorldServer) playerIn.world);
        playerIn.setPositionAndRotation(x, y, z, yaw, pitch);
        // make sure the chunk is loaded for login
        worldServer.getChunkProvider().loadChunk(loginEvent.getToTransform().getLocation().getChunkPosition().getX(), loginEvent.getToTransform().getLocation().getChunkPosition().getZ());
        // Sponge end

        String s1 = "local";

        if (netManager.getRemoteAddress() != null) {
            s1 = netManager.getRemoteAddress().toString();
        }

        final WorldInfo worldinfo = worldServer.getWorldInfo();
        final BlockPos spawnBlockPos = worldServer.getSpawnPoint();
        this.setPlayerGameTypeBasedOnOther(playerIn, null, worldServer);

        // Sponge start
        if (handler == null) {
            // Create the handler here (so the player's gets set)
            handler = new NetHandlerPlayServer(this.server, netManager, playerIn);
        }
        playerIn.connection = handler;
        SpongeImplHooks.fireServerConnectionEvent(netManager);
        // Sponge end

        // Support vanilla clients logging into custom dimensions
        final int dimensionId = WorldManager.getClientDimensionId(playerIn, worldServer);

        // Send dimension registration
        WorldManager.sendDimensionRegistration(playerIn, worldServer.provider);

        handler.sendPacket(new SPacketJoinGame(playerIn.getEntityId(), playerIn.interactionManager.getGameType(), worldinfo
                .isHardcoreModeEnabled(), dimensionId, worldServer.getDifficulty(), this.getMaxPlayers(), worldinfo
                .getTerrainType(), worldServer.getGameRules().getBoolean("reducedDebugInfo")));
        handler.sendPacket(new SPacketCustomPayload("MC|Brand", (new PacketBuffer(Unpooled.buffer())).writeString(this
                .getServerInstance().getServerModName())));
        handler.sendPacket(new SPacketServerDifficulty(worldinfo.getDifficulty(), worldinfo.isDifficultyLocked()));
        handler.sendPacket(new SPacketSpawnPosition(spawnBlockPos));
        handler.sendPacket(new SPacketPlayerAbilities(playerIn.capabilities));
        handler.sendPacket(new SPacketHeldItemChange(playerIn.inventory.currentItem));
        this.updatePermissionLevel(playerIn);
        playerIn.getStatFile().markAllDirty();
        playerIn.getRecipeBook().init(playerIn);
        this.server.refreshStatusNextTick();

        handler.setPlayerLocation(x, y, z, yaw, pitch);
        this.playerLoggedIn(playerIn);

        // Sponge start - add world name to message
        LOGGER.info(playerIn.getName() + "[" + s1 + "] logged in with entity id " + playerIn.getEntityId() + " in "
                + worldServer.getWorldInfo().getWorldName() + "(" + ((ServerWorldBridge) worldServer).getDimensionId()
                + ") at (" + playerIn.posX + ", " + playerIn.posY + ", " + playerIn.posZ + ")");
        // Sponge end

        this.updateTimeAndWeatherForPlayer(playerIn, worldServer);

        // Sponge Start - Use the server's ResourcePack object
        Optional<ResourcePack> pack = ((Server)this.server).getDefaultResourcePack();
        pack.ifPresent(((Player) playerIn)::sendResourcePack);
        // Sponge End

        // Sponge Start
        //
        // This sends the objective/score creation packets
        // to the player, without attempting to remove them from their
        // previous scoreboard (which is set in a field initializer).
        // This allows #getWorldScoreboard to function
        // as normal, without causing issues when it is initialized on the client.

        ((IMixinEntityPlayerMP) playerIn).initScoreboard();

        for (PotionEffect potioneffect : playerIn.getActivePotionEffects()) {
            handler.sendPacket(new SPacketEntityEffect(playerIn.getEntityId(), potioneffect));
        }

        if (nbttagcompound != null) {
            if (nbttagcompound.hasKey("RootVehicle", 10)) {
                NBTTagCompound nbttagcompound1 = nbttagcompound.getCompoundTag("RootVehicle");
                Entity entity2 = AnvilChunkLoader.readWorldEntity(nbttagcompound1.getCompoundTag("Entity"), worldServer, true);

                if (entity2 != null) {
                    UUID uuid = nbttagcompound1.getUniqueId("Attach");

                    if (entity2.getUniqueID().equals(uuid)) {
                        playerIn.startRiding(entity2, true);
                    } else {
                        for (Entity entity : entity2.getRecursivePassengers()) {
                            if (entity.getUniqueID().equals(uuid)) {
                                playerIn.startRiding(entity, true);
                                break;
                            }
                        }
                    }

                    if (!playerIn.isRiding()) {
                        LOGGER.warn("Couldn\'t reattach entity to player");
                        worldServer.removeEntityDangerously(entity2);

                        for (Entity entity3 : entity2.getRecursivePassengers()) {
                            worldServer.removeEntityDangerously(entity3);
                        }
                    }
                }
            } else if (nbttagcompound.hasKey("Riding", 10)) {
                Entity entity1 = AnvilChunkLoader.readWorldEntity(nbttagcompound.getCompoundTag("Riding"), worldServer, true);

                if (entity1 != null) {
                    playerIn.startRiding(entity1, true);
                }
            }
        }

        playerIn.addSelfToInternalCraftingInventory();

        TextComponentTranslation chatcomponenttranslation;

        if (!playerIn.getName().equalsIgnoreCase(s))
        {
            chatcomponenttranslation = new TextComponentTranslation("multiplayer.player.joined.renamed", playerIn.getDisplayName(), s);
        }
        else
        {
            chatcomponenttranslation = new TextComponentTranslation("multiplayer.player.joined", playerIn.getDisplayName());
        }

        chatcomponenttranslation.getStyle().setColor(TextFormatting.YELLOW);

        // Fire PlayerJoinEvent
        Text originalMessage = SpongeTexts.toText(chatcomponenttranslation);
        MessageChannel originalChannel = player.getMessageChannel();
        Sponge.getCauseStackManager().pushCause(player);
        final ClientConnectionEvent.Join event = SpongeEventFactory.createClientConnectionEventJoin(
                Sponge.getCauseStackManager().getCurrentCause(), originalChannel, Optional.of(originalChannel),
                new MessageEvent.MessageFormatter(originalMessage), player, false
        );
        SpongeImpl.postEvent(event);
        Sponge.getCauseStackManager().popCause();
        // Send to the channel
        if (!event.isMessageCancelled()) {
            event.getChannel().ifPresent(channel -> channel.send(player, event.getMessage()));
        }
        // Sponge end
    }

    /**
     * @author Zidane - June 13th, 2015
     * @author simon816 - June 24th, 2015
     * @author Zidane - March 29th, 2016
     * @author gabizou - June 5th, 2016 - Update for teleportation changes to keep the same player.
     *
     * @reason - Direct respawning players to use Sponge events
     * and process appropriately.
     *
     * @param playerIn The player being respawned/created
     * @param targetDimension The target dimension
     * @param conqueredEnd Whether the end was conquered
     * @return The new player
     */
    @Overwrite
    public EntityPlayerMP recreatePlayerEntity(EntityPlayerMP playerIn, int targetDimension, boolean conqueredEnd) {
        // ### PHASE 1 ### Get the location to spawn

        // Vanilla will always use overworld, set to the world the player was in
        // UNLESS comming back from the end.
        if (!conqueredEnd && targetDimension == 0) {
            targetDimension = playerIn.dimension;
        }

        if (playerIn.isBeingRidden()) {
            playerIn.removePassengers();
        }

        if (playerIn.isRiding()) {
            playerIn.dismountRidingEntity();
        }

        final Player player = (Player) playerIn;
        final Transform<World> fromTransform = player.getTransform();
        WorldServer worldServer = this.server.getWorld(targetDimension);
        Transform<World> toTransform = new Transform<>(EntityUtil.getPlayerRespawnLocation(playerIn, worldServer), Vector3d.ZERO, Vector3d.ZERO);
        targetDimension = ((ServerWorldBridge) toTransform.getExtent()).getDimensionId();
        Location<World> location = toTransform.getLocation();

        // If coming from end, fire a teleport event for plugins
        if (conqueredEnd) {
            // When leaving the end, players are never placed inside the teleporter but instead "respawned" in the target world
            MoveEntityEvent.Teleport teleportEvent = EntityUtil.handleDisplaceEntityTeleportEvent(playerIn, location);
            if (teleportEvent.isCancelled()) {
                playerIn.queuedEndExit = false;
                return playerIn;
            }

            toTransform = teleportEvent.getToTransform();
            location = toTransform.getLocation();
        }
        // Keep players out of blocks
        Vector3d tempPos = player.getLocation().getPosition();
        playerIn.setPosition(location.getX(), location.getY(), location.getZ());
        while (!((WorldServer) location.getExtent()).getCollisionBoxes(playerIn, playerIn.getEntityBoundingBox()).isEmpty() && location.getPosition().getY() < 256.0D) {
            playerIn.setPosition(playerIn.posX, playerIn.posY + 1.0D, playerIn.posZ);
            location = location.add(0, 1, 0);
        }
        playerIn.setPosition(tempPos.getX(), tempPos.getY(), tempPos.getZ());

        // ### PHASE 2 ### Remove player from current dimension
        playerIn.getServerWorld().getEntityTracker().removePlayerFromTrackers(playerIn);
        playerIn.getServerWorld().getEntityTracker().untrack(playerIn);
        playerIn.getServerWorld().getPlayerChunkMap().removePlayer(playerIn);
        this.playerEntityList.remove(playerIn);
        this.server.getWorld(playerIn.dimension).removeEntityDangerously(playerIn);
        final BlockPos bedPos = SpongeImplHooks.getBedLocation(playerIn, targetDimension);

        // ### PHASE 3 ### Reset player (if applicable)
        // Recreate the player object in order to support Forge's PlayerEvent.Clone
        PlayerInteractionManager playerinteractionmanager;

        if (this.server.isDemo()) {
            playerinteractionmanager = new DemoPlayerInteractionManager(this.server.getWorld(targetDimension));
        } else {
            playerinteractionmanager = new PlayerInteractionManager(this.server.getWorld(targetDimension));
        }

        EntityPlayerMP newPlayer = new EntityPlayerMP(SpongeImpl.getServer(), worldServer, playerIn.getGameProfile(), playerinteractionmanager);
        newPlayer.connection = playerIn.connection;
        newPlayer.copyFrom(playerIn, conqueredEnd);
        // set player dimension for RespawnPlayerEvent
        newPlayer.dimension = targetDimension;
        newPlayer.setEntityId(playerIn.getEntityId());
        newPlayer.setCommandStats(playerIn);
        newPlayer.setPrimaryHand(playerIn.getPrimaryHand());

        // Sponge - Vanilla does this before recreating the player entity. However, we need to determine the bed location
        // before respawning the player, so we know what dimension to spawn them into. This means that the bed location must be copied
        // over to the new player
        if (bedPos != null && EntityUtil.tempIsBedSpawn) {
            newPlayer.setSpawnPoint(bedPos, playerIn.isSpawnForced());
        }

        ((IMixinEntityPlayerMP) newPlayer).setScoreboardOnRespawn(((Player) playerIn).getScoreboard());
        ((IMixinEntityPlayerMP) playerIn).removeScoreboardOnRespawn();

        for (String s : playerIn.getTags()) {
            newPlayer.addTag(s);
        }

        this.setPlayerGameTypeBasedOnOther(newPlayer, playerIn, worldServer);
        newPlayer.setSneaking(false);

        // update to safe location
        toTransform = toTransform.setLocation(location);

        // ### PHASE 4 ### Fire event and set new location on the player
        Sponge.getCauseStackManager().pushCause(newPlayer);
        final RespawnPlayerEvent event = SpongeEventFactory.createRespawnPlayerEvent(Sponge.getCauseStackManager().getCurrentCause(), fromTransform,
                toTransform, (Player) playerIn, (Player) newPlayer, EntityUtil.tempIsBedSpawn, !conqueredEnd);
        EntityUtil.tempIsBedSpawn = false;
        SpongeImpl.postEvent(event);
        Sponge.getCauseStackManager().popCause();
        ((EntityBridge) player).setLocationAndAngles(event.getToTransform());
        toTransform = event.getToTransform();
        location = toTransform.getLocation();

        if (!(location.getExtent() instanceof WorldServer)) {
            SpongeImpl.getLogger().warn("Location set in PlayerRespawnEvent was invalid, using original location instead");
            location = event.getFromTransform().getLocation();
        }
        worldServer = (WorldServer) location.getExtent();

        final ServerWorldBridge mixinWorldServer = (ServerWorldBridge) worldServer;
        // Set the dimension again in case a plugin changed the target world during RespawnPlayerEvent
        newPlayer.dimension = mixinWorldServer.getDimensionId();
        newPlayer.setWorld(worldServer);
        newPlayer.interactionManager.setWorld(worldServer);

        worldServer.getChunkProvider().loadChunk((int) location.getX() >> 4, (int) location.getZ() >> 4);

        // ### PHASE 5 ### Respawn player in new world

        // Support vanilla clients logging into custom dimensions
        final int dimensionId = WorldManager.getClientDimensionId(newPlayer, worldServer);

        // Send dimension registration
        if (((IMixinEntityPlayerMP) newPlayer).usesCustomClient()) {
            WorldManager.sendDimensionRegistration(newPlayer, worldServer.provider);
        } else {
            // Force vanilla client to refresh its chunk cache if same dimension type
            if (fromTransform.getExtent().getUniqueId() != ((World) worldServer).getUniqueId() && fromTransform.getExtent().getDimension().getType() ==
              toTransform.getExtent().getDimension().getType()) {
                newPlayer.connection.sendPacket(new SPacketRespawn((dimensionId >= 0 ? -1 : 0), worldServer.getDifficulty(), worldServer
                        .getWorldInfo().getTerrainType(), newPlayer.interactionManager.getGameType()));
            }
        }
        newPlayer.connection.sendPacket(new SPacketRespawn(dimensionId, worldServer.getDifficulty(), worldServer
                .getWorldInfo().getTerrainType(), newPlayer.interactionManager.getGameType()));
        newPlayer.connection.sendPacket(new SPacketServerDifficulty(worldServer.getDifficulty(), worldServer.getWorldInfo().isDifficultyLocked()));
        newPlayer.connection.setPlayerLocation(location.getX(), location.getY(), location.getZ(),
                (float) toTransform.getYaw(), (float) toTransform.getPitch());

        final BlockPos spawnLocation = worldServer.getSpawnPoint();
        newPlayer.connection.sendPacket(new SPacketSpawnPosition(spawnLocation));
        newPlayer.connection.sendPacket(new SPacketSetExperience(newPlayer.experience, newPlayer.experienceTotal,
                newPlayer.experienceLevel));
        this.updateTimeAndWeatherForPlayer(newPlayer, worldServer);
        this.updatePermissionLevel(newPlayer);
        worldServer.getPlayerChunkMap().addPlayer(newPlayer);
        org.spongepowered.api.entity.Entity spongeEntity = (org.spongepowered.api.entity.Entity) newPlayer;
        ((org.spongepowered.api.world.World) worldServer).spawnEntity(spongeEntity);
        this.playerEntityList.add(newPlayer);
        newPlayer.connection.sendPacket(new SPacketPlayerListItem(SPacketPlayerListItem.Action.UPDATE_GAME_MODE, newPlayer));
        this.uuidToPlayerMap.put(newPlayer.getUniqueID(), newPlayer);
        newPlayer.addSelfToInternalCraftingInventory();

        // Update reducedDebugInfo game rule
        newPlayer.connection.sendPacket(new SPacketEntityStatus(newPlayer,
                worldServer.getGameRules().getBoolean(DefaultGameRules.REDUCED_DEBUG_INFO) ? (byte) 22 : 23));

        for (PotionEffect potioneffect : newPlayer.getActivePotionEffects()) {
            newPlayer.connection.sendPacket(new SPacketEntityEffect(newPlayer.getEntityId(), potioneffect));
        }
        ((IMixinEntityPlayerMP) newPlayer).refreshScaledHealth();
        SpongeCommonEventFactory.callPostPlayerRespawnEvent(newPlayer, conqueredEnd);

        return newPlayer;
    }

    /**
     * @author blood - May 21st, 2016
     * @author gabizou - June 2nd, 2016 - Update for 1.9.4 and cause tracker changes
     *
     * @reason - adjusted to support {@link MoveEntityEvent.Teleport.Portal}
     *
     * @param playerIn The player teleporting to another dimension
     * @param targetDimensionId The id of target dimension.
     * @param teleporter The teleporter used to transport and create the portal
     */
    @Override
    public void transferPlayerToDimension(EntityPlayerMP playerIn, int targetDimensionId, Teleporter teleporter) {
        MoveEntityEvent.Teleport.Portal event = EntityUtil.handleDisplaceEntityPortalEvent(playerIn, targetDimensionId, (IMixinITeleporter) teleporter);
        if (event == null || event.isCancelled()) {
            return;
        }

        EntityUtil.transferPlayerToDimension(event, playerIn);
    }

    // copy of transferEntityToWorld but only contains code to apply the location on entity before being placed into a portal
    @Override
    public void prepareEntityForPortal(Entity entityIn, WorldServer oldWorldIn, WorldServer toWorldIn) {
        oldWorldIn.profiler.startSection("moving");
        WorldProvider pOld = oldWorldIn.provider;
        WorldProvider pNew = toWorldIn.provider;
        double moveFactor = getMovementFactor(pOld) / getMovementFactor(pNew);
        double x = entityIn.posX * moveFactor;
        double y = entityIn.posY;
        double z = entityIn.posZ * moveFactor;

//        if (!(pNew instanceof WorldProviderEnd)) {
//            x = MathHelper.clamp_double(x, toWorldIn.getWorldBorder().minX() + 16.0D, toWorldIn.getWorldBorder().maxX() - 16.0D);
//            z = MathHelper.clamp_double(z, toWorldIn.getWorldBorder().minZ() + 16.0D, toWorldIn.getWorldBorder().maxZ() - 16.0D);
//            entityIn.setLocationAndAngles(x, entityIn.posY, z, entityIn.rotationYaw, entityIn.rotationPitch);
//        }

        if (pNew instanceof WorldProviderEnd) {
            BlockPos blockpos;

            if (pOld instanceof WorldProviderEnd) {
                blockpos = toWorldIn.getSpawnPoint();
            } else {
                blockpos = toWorldIn.getSpawnCoordinate();
            }

            x = blockpos.getX();
            y = blockpos.getY();
            z = blockpos.getZ();
            entityIn.setLocationAndAngles(x, y, z, 90.0F, 0.0F);
        }

        if (!(pOld instanceof WorldProviderEnd)) {
            oldWorldIn.profiler.startSection("placing");
            x = MathHelper.clamp((int)x, -29999872, 29999872);
            z = MathHelper.clamp((int)z, -29999872, 29999872);

            if (entityIn.isEntityAlive()) {
                entityIn.setLocationAndAngles(x, y, z, entityIn.rotationYaw, entityIn.rotationPitch);
            }
            oldWorldIn.profiler.endSection();
        }

        if (entityIn.isEntityAlive()) {
            oldWorldIn.updateEntityWithOptionalForce(entityIn, false);
        }

        oldWorldIn.profiler.endSection();
    }

    /**
     * @author blood - May 21st, 2016
     *
     * @reason - overwritten to redirect to our method that accepts a teleporter
     */
    @Overwrite
    public void transferEntityToWorld(Entity entityIn, int p_82448_2_, WorldServer oldWorldIn, WorldServer toWorldIn) {
        transferEntityToWorld(entityIn, p_82448_2_, oldWorldIn, toWorldIn, toWorldIn.getDefaultTeleporter());
    }

    /**
     * @author blood - May 21st, 2016
     *
     * @reason - rewritten to capture a plugin or mod that attempts to call this method directly.
     *
     * @param entityIn The entity being teleported
     * @param fromDimensionId The origin dimension id
     * @param fromWorld The origin world
     * @param toWorld The destination world
     * @param teleporter The teleporter being used to transport the entity
     */
    @Override
    public void transferEntityToWorld(Entity entityIn, int fromDimensionId, WorldServer fromWorld, WorldServer toWorld, net.minecraft.world.Teleporter teleporter) {
        // rewritten completely to handle our portal event
        MoveEntityEvent.Teleport.Portal event = EntityUtil
                .handleDisplaceEntityPortalEvent(entityIn, WorldManager.getDimensionId(toWorld), (IMixinITeleporter) teleporter);
        if (event == null || event.isCancelled()) {
            return;
        }

        entityIn.setLocationAndAngles(event.getToTransform().getPosition().getX(), event.getToTransform().getPosition().getY(), event.getToTransform().getPosition().getZ(), (float) event.getToTransform().getYaw(), (float) event.getToTransform().getPitch());
        toWorld.spawnEntity(entityIn);
        toWorld.updateEntityWithOptionalForce(entityIn, false);
        entityIn.setWorld(toWorld);
    }

    // forge utility method
    @Override
    public double getMovementFactor(WorldProvider provider) {
        if (provider instanceof WorldProviderHell) {
            return 8.0;
        }
        return 1.0;
    }

    @Inject(method = "setPlayerManager", at = @At("HEAD"), cancellable = true)
    private void onSetPlayerManager(WorldServer[] worlds, CallbackInfo callbackInfo) {
        if (this.playerDataManager == null) {
            this.playerDataManager = worlds[0].getSaveHandler().getPlayerNBTManager();
            // This is already added in our world constructor
            //worlds[0].getWorldBorder().addListener(new PlayerBorderListener(0));
        }
        callbackInfo.cancel();
    }

    @Redirect(method = "updateTimeAndWeatherForPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;getWorldBorder()Lnet/minecraft/world/border/WorldBorder;"))
    private WorldBorder onUpdateTimeGetWorldBorder(WorldServer worldServer, EntityPlayerMP entityPlayerMP, WorldServer worldServerIn) {
        return worldServerIn.getWorldBorder();
    }

    @Redirect(method = "updateTimeAndWeatherForPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetHandlerPlayServer;sendPacket"
            + "(Lnet/minecraft/network/Packet;)V", ordinal = 0))
    public void onWorldBorderInitializePacket(NetHandlerPlayServer invoker, Packet<?> packet, EntityPlayerMP playerMP, WorldServer worldServer) {
        if (worldServer.provider instanceof WorldProviderHell) {
            ((WorldBorderPacketBridge) packet).bridge$changeCoordinatesForNether();
        }

        invoker.sendPacket(packet);
    }

    @Inject(method = "playerLoggedOut(Lnet/minecraft/entity/player/EntityPlayerMP;)V", at = @At("HEAD"))
    private void onPlayerLogOut(EntityPlayerMP player, CallbackInfo ci) {
        // Synchronise with user object
        NBTTagCompound nbt = new NBTTagCompound();
        player.writeToNBT(nbt);
        ((SpongeUser) ((IMixinEntityPlayerMP) player).getUserObject()).readFromNbt(nbt);

        // Remove player reference from scoreboard
        ((IMixinServerScoreboard) ((Player) player).getScoreboard()).removePlayer(player, false);
    }

    @Redirect(method = "playerLoggedOut(Lnet/minecraft/entity/player/EntityPlayerMP;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;removeEntity(Lnet/minecraft/entity/Entity;)V"))
    private void onPlayerRemoveFromWorldFromDisconnect(WorldServer world, Entity player, EntityPlayerMP playerMP) {
        try (final GeneralizedContext context = PlayerPhase.State.PLAYER_LOGOUT.createPhaseContext().source(playerMP).addCaptures()) {
            context.buildAndSwitch();
            world.removeEntity(player);
        }
    }

    @Inject(method = "saveAllPlayerData()V", at = @At("RETURN"))
    private void onSaveAllPlayerData(CallbackInfo ci) {
        for (SpongeUser user : SpongeUser.dirtyUsers) {
            user.save();
        }
    }

    @Inject(method = "playerLoggedIn", at = @At(value = "INVOKE", target = SERVER_SEND_PACKET_TO_ALL_PLAYERS, shift = At.Shift.BEFORE), cancellable = true)
    public void playerLoggedIn2(EntityPlayerMP player, CallbackInfo ci) {
        // Create a packet to be used for players without context data
        SPacketPlayerListItem noSpecificViewerPacket = new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, player);

        for (EntityPlayerMP viewer : this.playerEntityList) {
            if (((Player) viewer).canSee((Player) player)) {
                viewer.connection.sendPacket(noSpecificViewerPacket);
            }

            if (player == viewer || ((Player) player).canSee((Player) viewer)) {
                player.connection.sendPacket(new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, viewer));
            }
        }

        // Spawn player into level
        WorldServer level = this.server.getWorld(player.dimension);
        // TODO direct this appropriately
        level.spawnEntity(player);
        this.preparePlayer(player, null);

        // We always want to cancel.
        ci.cancel();
    }

    @Inject(method = "writePlayerData", at = @At(target = WRITE_PLAYER_DATA, value = "INVOKE"))
    private void onWritePlayerFile(EntityPlayerMP playerMP, CallbackInfo callbackInfo) {
        SpongePlayerDataHandler.savePlayer(playerMP.getUniqueID());
    }

    @ModifyVariable(method = "sendPlayerPermissionLevel", at = @At("HEAD"), argsOnly = true)
    public int fixPermLevel(int permLevel) {
        // If a non-default permission service is being used, then the op level will always be 0.
        // We force it to be 4 to ensure that the client is able to open command blocks (
        if (!(Sponge.getServiceManager().provideUnchecked(PermissionService.class) instanceof SpongePermissionService)) {
            return 4;
        }
        return permLevel;
    }

    @Redirect(method = "updatePermissionLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldServer;getWorldInfo()Lnet/minecraft/world/storage/WorldInfo;"))
    private WorldInfo onGetWorldInfo(WorldServer overworld, EntityPlayerMP player) {
        // TODO: This applies only to singleplayer, on the server canSendCommands is called with the game profile
        // We can't get the world from the game profile

        // Check the world info of the current world instead of overworld world info
        return player.world.getWorldInfo();
    }

    /**
     * @author simon816 - 14th November, 2016
     *
     * @reason Redirect chat broadcasts to fire an event for the message. Each receiver
     * (typically a player) will handle the actual sending of the message.
     *
     * @param component The message
     * @param isSystem Whether this is a system message
     */
    @Overwrite
    public void sendMessage(ITextComponent component, boolean isSystem) {
        ChatUtil.sendMessage(component, MessageChannel.TO_ALL, (CommandSource) this.server, !isSystem);
    }

    @Inject(method = "createPlayerForUser", at = @At("RETURN"), cancellable = true)
    public void onCreatePlayerForUser(CallbackInfoReturnable<EntityPlayerMP> cir) {
        ((IMixinEntityPlayerMP) cir.getReturnValue()).forceRecreateUser();
    }

    @Override
    public void reloadAdvancementProgress() {
        for (PlayerAdvancements playerAdvancements : this.advancements.values()) {
            ((IMixinPlayerAdvancements) playerAdvancements).reloadAdvancementProgress();
        }
    }
}
