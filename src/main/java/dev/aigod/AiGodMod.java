package dev.aigod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiGodMod implements ModInitializer {
    public static final String MOD_ID = "ai_god";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static GodService god;
    private static AdminServer admin;

    @Override
    public void onInitialize() {
        // Route player chat through the mod and rebroadcast it as a system message. On this
        // server the vanilla signed-chat path does not reach clients (they see system messages
        // like the god's, but not each other's signed chat), so we cancel vanilla delivery
        // (return false) and re-send every message through the system path that works.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, parameters) -> {
            if (god == null) return true;
            god.hear(player, message.signedContent());
            return false;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (god != null) god.playerJoined(handler.getPlayer());
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                LOGGER.warn("AI God disabled: OPENAI_API_KEY is not set");
                return;
            }
            String model = System.getenv().getOrDefault("AI_GOD_MODEL", "gpt-5.6-luna");
            String godName = System.getenv().getOrDefault("AI_GOD_NAME", "AI God");
            int compactThreshold = positiveEnvironmentInt("AI_GOD_COMPACT_THRESHOLD", 100_000);
            var worldPath = server.getWorldPath(LevelResource.ROOT);
            QuestStore store = new QuestStore(
                    worldPath.resolve("ai-god-quests.json"), LOGGER);
            DailyStore dailyStore = new DailyStore(
                    worldPath.resolve("ai-god-daily.json"), LOGGER);
            ConversationStore conversationStore = new ConversationStore(
                    worldPath.resolve("ai-god-conversation.txt"), LOGGER);
            ScheduleStore scheduleStore = new ScheduleStore(
                    worldPath.resolve("ai-god-schedules.json"), LOGGER);
            god = new GodService(server, apiKey, model, godName, compactThreshold,
                    store, dailyStore, conversationStore, scheduleStore);
            String adminPassword = System.getenv("AI_GOD_ADMIN_PASSWORD");
            if (adminPassword == null || adminPassword.isBlank()) {
                LOGGER.warn("Admin page disabled: AI_GOD_ADMIN_PASSWORD is not set");
            } else {
                try {
                    admin = new AdminServer(apiKey, model, conversationStore,
                            positiveEnvironmentInt("AI_GOD_ADMIN_PORT", 8765), adminPassword,
                            god::adminState, LOGGER);
                } catch (java.io.IOException exception) {
                    LOGGER.error("Could not start the local AI God admin server", exception);
                }
            }
            LOGGER.info("{} awakened using {}", godName, model);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (admin != null) admin.close();
            admin = null;
            if (god != null) god.close();
            god = null;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (god == null) return;
            if (damageSource.getEntity() instanceof ServerPlayer player) {
                god.recordKill(player, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                        entity instanceof ServerPlayer victim ? victim.getGameProfile().name() : null);
            }
            if (entity instanceof ServerPlayer victim) {
                god.playerDied(victim, damageSource.getLocalizedDeathMessage(victim).getString());
            }
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) -> {
            if (god != null && player instanceof ServerPlayer serverPlayer) {
                god.recordMine(serverPlayer, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (god != null) god.tick();
        });
    }

    private static int positiveEnvironmentInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // Fall through to the documented default.
        }
        LOGGER.warn("Ignoring invalid {}={}; using {}", name, value, fallback);
        return fallback;
    }
}
