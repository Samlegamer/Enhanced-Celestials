package corgitaco.enhancedcelestials;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import corgitaco.enhancedcelestials.api.EnhancedCelestialsRegistry;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import corgitaco.enhancedcelestials.lunarevent.Moon;
import corgitaco.enhancedcelestials.network.NetworkHandler;
import corgitaco.enhancedcelestials.network.packet.LunarEventChangedPacket;
import corgitaco.enhancedcelestials.network.packet.LunarForecastChangedPacket;
import corgitaco.enhancedcelestials.save.LunarEventSavedData;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class LunarContext {

    public static final String CONFIG_NAME = "lunar-settings.json";
    private static final LunarEvent DEFAULT = Moon.MOON;

    public static final Codec<LunarContext> PACKET_CODEC = RecordCodecBuilder.create((builder) -> {
        return builder.group(LunarForecast.CODEC.fieldOf("lunarForecast").forGetter((lunarContext) -> {
            return lunarContext.lunarForecast;
        }), LunarTimeSettings.CODEC.fieldOf("lunarTimeSettings").forGetter((lunarContext) -> {
            return lunarContext.lunarTimeSettings;
        }), ResourceLocation.CODEC.fieldOf("worldID").forGetter((weatherEventContext) -> {
            return weatherEventContext.worldID;
        }), Codec.unboundedMap(Codec.STRING, LunarEvent.CODEC).fieldOf("weatherEvents").forGetter((weatherEventContext) -> {
            return weatherEventContext.lunarEvents;
        })).apply(builder, LunarContext::new);
    });

    private final Map<String, LunarEvent> lunarEvents = new HashMap<>();
    private final LunarForecast lunarForecast;
    private final ResourceLocation worldID;
    private final Path lunarConfigPath;
    private final Path lunarEventsConfigPath;

    private final LunarTimeSettings lunarTimeSettings;
    private final long dayLength; // TODO: Config
    private final long yearLengthInDays; // TODO: Config
    private final long minDaysBetweenEvents; // TODO: Config
    private final ArrayList<String> scrambledKeys = new ArrayList<>();
    private LunarEvent currentEvent;
    private LunarEvent lastEvent;
    private float strength;

    public LunarContext(ServerWorld world) {
        this.worldID = world.getDimensionKey().getLocation();
        this.lunarConfigPath = Main.CONFIG_PATH.resolve(worldID.getNamespace()).resolve(worldID.getPath()).resolve("lunar");
        this.lunarEventsConfigPath = this.lunarConfigPath.resolve("events");
        this.lunarTimeSettings = readOrCreateConfigJson(this.lunarConfigPath.resolve(CONFIG_NAME).toFile());
        this.dayLength = lunarTimeSettings.dayLength;
        this.yearLengthInDays = lunarTimeSettings.yearLength;
        this.minDaysBetweenEvents = lunarTimeSettings.minDaysBetweenLunarEvents;
        handleEventConfigs(false);
        this.scrambledKeys.addAll(this.lunarEvents.keySet());
        this.lunarForecast = getAndComputeLunarForecast(world).getForecast();
        assert lunarForecast != null;
        LunarEventInstance nextLunarEvent = lunarForecast.getForecast().get(0);
        this.currentEvent = nextLunarEvent.getDaysUntil((int) (world.getDayTime() / this.dayLength)) <= 0 && world.isNightTime() ? nextLunarEvent.getEvent(this.lunarEvents) : DEFAULT;
    }

    // Packet Codec Constructor
    public LunarContext(LunarForecast lunarForecast, LunarTimeSettings lunarTimeSettings, ResourceLocation worldID, Map<String, LunarEvent> lunarEvents) {
        this(lunarForecast, lunarTimeSettings, worldID, lunarEvents, false);
    }

    // Client Constructor
    public LunarContext(LunarForecast lunarForecast, LunarTimeSettings lunarTimeSettings, ResourceLocation worldID, Map<String, LunarEvent> lunarEvents, boolean serializeClientOnlyConfigs) {
        this.worldID = worldID;
        this.lunarConfigPath = Main.CONFIG_PATH.resolve(worldID.getNamespace()).resolve(worldID.getPath()).resolve("lunar");
        this.lunarEventsConfigPath = this.lunarConfigPath.resolve("events");
        this.lunarEvents.putAll(lunarEvents);
        LunarEventInstance nextLunarEvent = lunarForecast.getForecast().get(0);
        this.currentEvent = nextLunarEvent.scheduledDay() == 0 ? nextLunarEvent.getEvent(this.lunarEvents) : DEFAULT;
        this.lunarForecast = lunarForecast;
        this.lunarTimeSettings = lunarTimeSettings;
        this.dayLength = lunarTimeSettings.dayLength;
        this.yearLengthInDays = lunarTimeSettings.yearLength;
        this.minDaysBetweenEvents = lunarTimeSettings.minDaysBetweenLunarEvents;
        if (serializeClientOnlyConfigs) {
            this.handleEventConfigs(true);
        }
        this.lunarEvents.forEach((key, event) -> event.setKey(key));
    }

    public LunarEventSavedData getAndComputeLunarForecast(ServerWorld world) {
        LunarEventSavedData lunarEventSavedData = LunarEventSavedData.get(world);
        if (lunarEventSavedData.getForecast() == null) {
            lunarEventSavedData.setForecast(computeLunarForecast(world, new LunarForecast(new ArrayList<>(), world.getDayTime())));
        }
        lunarEventSavedData.getForecast().getForecast().removeIf(lunarEventInstance -> !this.lunarEvents.containsKey(lunarEventInstance.getLunarEventKey()));
        lunarEventSavedData.setForecast(lunarEventSavedData.getForecast());
        return lunarEventSavedData;
    }

    public LunarForecast computeLunarForecast(ServerWorld world, LunarForecast lunarForecast) {
        return computeLunarForecast(world, lunarForecast, 0L);
    }

    public LunarForecast computeLunarForecast(ServerWorld world, LunarForecast lunarForecast, long seedModifier) {
        long dayTime = world.getDayTime();
        long lastCheckedTime = lunarForecast.getLastCheckedGameTime();

        long currentDay = dayTime / dayLength;
        long lastCheckedDay = lastCheckedTime / dayLength;

        if (lastCheckedDay < currentDay) {
            lunarForecast.getForecast().clear();
            lunarForecast.setLastCheckedGameTime(currentDay * dayLength);
            lastCheckedTime = lunarForecast.getLastCheckedGameTime();
            lastCheckedDay = lastCheckedTime / dayLength;
        }

        if (currentDay + this.yearLengthInDays == lastCheckedDay) {
            return lunarForecast;
        }

        List<LunarEventInstance> newLunarEvents = new ArrayList<>();

        Object2LongArrayMap<LunarEvent> eventByLastTime = new Object2LongArrayMap<>();
        List<LunarEventInstance> forecast = lunarForecast.getForecast();
        long lastDay = !forecast.isEmpty() ? forecast.get(forecast.size() - 1).scheduledDay() : currentDay;

        long day = lastCheckedDay;

        for (LunarEventInstance lunarEventInstance : forecast) {
            eventByLastTime.put(lunarEventInstance.getEvent(this.lunarEvents), lunarEventInstance.scheduledDay());
        }

        for (; day <= currentDay + this.yearLengthInDays; day++) {
            dayTime += this.dayLength;
            Random random = new Random(world.getSeed() + world.getDimensionKey().getLocation().hashCode() + day + seedModifier);
            Collections.shuffle(scrambledKeys, random);
            for (String key : scrambledKeys) {
                LunarEvent value = this.lunarEvents.get(key);
                if ((day - eventByLastTime.getOrDefault(value, currentDay)) > value.getMinNumberOfNightsBetween() && (day - lastDay) > this.minDaysBetweenEvents && value.getChance() > random.nextDouble() && value.getValidMoonPhases().contains(world.getDimensionType().getMoonPhase(dayTime - 1))) {
                    lastDay = day;
                    newLunarEvents.add(new LunarEventInstance(key, day));
                    eventByLastTime.put(value, day);
                }
            }
        }
        forecast.addAll(newLunarEvents);
        lunarForecast.setLastCheckedGameTime(day * dayLength);
        return lunarForecast;
    }


    public void tick(World world) {
        LunarEvent lastEvent = this.currentEvent;
        long currentDay = (world.getDayTime() / this.dayLength);
        if (!world.isRemote) {
            List<ServerPlayerEntity> players = ((ServerWorld) world).getPlayers();
            updateForecast(world, currentDay, players);
            LunarEventInstance nextEvent = this.getLunarForecast().getForecast().get(0);
            if (!this.getLunarForecast().getForecast().isEmpty()) {
                this.currentEvent = nextEvent.getDaysUntil(currentDay) <= 0 && world.isNightTime() ? nextEvent.getEvent(this.lunarEvents) : DEFAULT;
            }

            if (this.currentEvent != lastEvent) {
                this.lastEvent = lastEvent;
                this.strength = 0;
                TranslationTextComponent endNotification = lastEvent.endNotification();
                if (endNotification != null) {
                    for (ServerPlayerEntity player : players) {
                        player.sendStatusMessage(endNotification, false);
                    }
                }

                TranslationTextComponent startNotification = this.currentEvent.startNotification();
                if (startNotification != null) {
                    for (ServerPlayerEntity player : players) {
                        player.sendStatusMessage(startNotification, false);
                    }
                }
                NetworkHandler.sendToAllPlayers(players, new LunarEventChangedPacket(this.currentEvent.getKey()));
            }
        }
        this.strength = MathHelper.clamp(this.strength + 0.01F, 0, 1.0F);
    }

    private void updateForecast(World world, long currentDay, List<ServerPlayerEntity> players) {
        updateForecast(world, currentDay);
        long lastCheckedGameTime = this.lunarForecast.getLastCheckedGameTime();
        LunarForecast newLunarForecast = computeLunarForecast((ServerWorld) world, this.lunarForecast);

        long newLastCheckedGameTime = newLunarForecast.getLastCheckedGameTime();
        long newLastCheckedDay = newLastCheckedGameTime / this.dayLength;
        long lastCheckedDay = lastCheckedGameTime / this.dayLength;
        if (newLastCheckedDay != lastCheckedDay) {
            NetworkHandler.sendToAllPlayers(players, new LunarForecastChangedPacket(this.lunarForecast));
            LunarEventSavedData.get(world).setForecast(lunarForecast);
        }
    }

    public void updateForecast(World world, long currentDay) {
        LunarEventInstance nextEvent = this.lunarForecast.getForecast().get(0);
        if (nextEvent.passed(currentDay)) {
            this.lunarForecast.getForecast().remove(0);
            NetworkHandler.sendToAllPlayers(((ServerWorld) world).getPlayers(), new LunarForecastChangedPacket(this.lunarForecast));
            LunarEventSavedData.get(world).setForecast(lunarForecast);
        }
    }

    public LunarEvent getCurrentEvent() {
        return currentEvent;
    }

    @Nullable
    public LunarEvent getLastEvent() {
        return lastEvent;
    }

    public void handleEventConfigs(boolean isClient) {
        if (isClient) {
            DEFAULT.setLunarEventClient(DEFAULT.getClientSettings().createClient());
        }
        File eventsDirectory = this.lunarEventsConfigPath.toFile();
        if (!eventsDirectory.exists()) {
            createDefaultEventConfigs();
        }

        File[] files = eventsDirectory.listFiles();

        if (files.length == 0) {
            createDefaultEventConfigs();
        }

        if (isClient) {
            addSettingsIfMissing();
        }

        iterateAndReadConfiguredEvents(files, isClient);
    }

    private void iterateAndReadConfiguredEvents(File[] files, boolean isClient) {
        for (File configFile : files) {
            String absolutePath = configFile.getAbsolutePath();
//            if (absolutePath.endsWith(".toml")) {
//                readToml(isClient, configFile);

//            } else if (absolutePath.endsWith(".json")) {
            readJson(isClient, configFile);
//            }
        }
    }


    public void createDefaultEventConfigs() {
        for (Map.Entry<ResourceLocation, LunarEvent> entry : EnhancedCelestialsRegistry.DEFAULT_EVENTS.entrySet()) {
            ResourceLocation location = entry.getKey();
            LunarEvent event = entry.getValue();
            Optional<RegistryKey<Codec<? extends LunarEvent>>> optionalKey = EnhancedCelestialsRegistry.LUNAR_EVENT.getOptionalKey(event.codec());

            if (optionalKey.isPresent()) {
//                if (BetterWeatherConfig.SERIALIZE_AS_JSON) {
                createJsonEventConfig(event, location.toString());
//                } else {
//                    createTomlEventConfig(event, location.toString());
//                }
            } else {
                throw new IllegalStateException("Weather Event Key for codec not there when requested: " + event.getClass().getSimpleName());
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void addSettingsIfMissing() {
        for (Map.Entry<String, LunarEvent> entry : this.lunarEvents.entrySet()) {
            LunarEvent event = entry.getValue();
            String key = entry.getKey();
            File tomlFile = this.lunarEventsConfigPath.resolve(key + ".toml").toFile();
            File jsonFile = this.lunarEventsConfigPath.resolve(key + ".json").toFile();
            Optional<RegistryKey<Codec<? extends LunarEvent>>> optionalKey = EnhancedCelestialsRegistry.LUNAR_EVENT.getOptionalKey(event.codec());

            if (optionalKey.isPresent()) {
                if (!tomlFile.exists() && !jsonFile.exists()) {
//                    if (BetterWeatherConfig.SERIALIZE_AS_JSON) {
                    createJsonEventConfig(event, key);
//                    } else {
//                        createTomlEventConfig(event, key);
//                    }
                }
            } else {
                throw new IllegalStateException("Weather Event Key for codec not there when requested: " + event.getClass().getSimpleName());
            }
        }
    }


    private void createJsonEventConfig(LunarEvent weatherEvent, String weatherEventID) {
        Path configFile = this.lunarEventsConfigPath.resolve(weatherEventID.replace(":", "-") + ".json");
        JsonElement jsonElement = LunarEvent.CODEC.encodeStart(JsonOps.INSTANCE, weatherEvent).result().get();

        try {
            Files.createDirectories(configFile.getParent());
            Files.write(configFile, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(jsonElement).getBytes());
        } catch (IOException e) {
            Main.LOGGER.error(e.toString());
        }
    }


    private void readJson(boolean isClient, File configFile) {
        try {
            String name = configFile.getName().replace(".json", "").toLowerCase();
            LunarEvent decodedValue = LunarEvent.CODEC.decode(JsonOps.INSTANCE, new JsonParser().parse(new FileReader(configFile))).resultOrPartial(Main.LOGGER::error).get().getFirst().setKey(name);
            if (isClient /*&& !BetterWeather.CLIENT_CONFIG.useServerClientSettings*/) {
                if (this.lunarEvents.containsKey(name)) {
                    LunarEvent lunarEvent = this.lunarEvents.get(name);
                    lunarEvent.setClientSettings(decodedValue.getClientSettings());
                    lunarEvent.setLunarEventClient(lunarEvent.getClientSettings().createClient());
                }
            } else {
                this.lunarEvents.put(name, decodedValue);
            }
        } catch (FileNotFoundException e) {
            Main.LOGGER.error(e.toString());
        }
    }

    private static LunarTimeSettings readOrCreateConfigJson(File configFile) {
        if (!configFile.exists()) {
            try {
                Path path = configFile.toPath();
                Files.createDirectories(path.getParent());
                JsonElement jsonElement = LunarTimeSettings.CODEC.encodeStart(JsonOps.INSTANCE, LunarTimeSettings.DEFAULT).result().get();
                Files.write(path, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(jsonElement).getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            return LunarTimeSettings.CODEC.decode(JsonOps.INSTANCE, new JsonParser().parse(new FileReader(configFile))).result().orElseThrow(RuntimeException::new).getFirst();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public LunarForecast getLunarForecast() {
        return lunarForecast;
    }

    public Map<String, LunarEvent> getLunarEvents() {
        return lunarEvents;
    }

    public LunarTimeSettings getLunarTimeSettings() {
        return lunarTimeSettings;
    }

    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = strength;
    }

    @OnlyIn(Dist.CLIENT)
    public void setLastEvent(LunarEvent lastEvent) {
        this.lastEvent = lastEvent;
    }

    @OnlyIn(Dist.CLIENT)
    public void setCurrentEvent(String currentEvent) {
        if (currentEvent.equals(DEFAULT.getKey())) {
            this.currentEvent = DEFAULT;
        } else {
            this.currentEvent = this.lunarEvents.get(currentEvent);
        }
    }

    public static class LunarTimeSettings {
        public static final Codec<LunarTimeSettings> CODEC = RecordCodecBuilder.create((builder) -> {
            return builder.group(Codec.LONG.fieldOf("daylength").forGetter((lunarTimeSettings) -> {
                return lunarTimeSettings.dayLength;
            }), Codec.LONG.fieldOf("yearLengthInDays").forGetter((lunarTimeSettings) -> {
                return lunarTimeSettings.yearLength;
            }), Codec.LONG.fieldOf("minDaysBetweenLunarEvents").forGetter((lunarTimeSettings) -> {
                return lunarTimeSettings.minDaysBetweenLunarEvents;
            })).apply(builder, LunarTimeSettings::new);
        });

        public static final LunarTimeSettings DEFAULT = new LunarTimeSettings(24000, 100, 5);

        private final long dayLength;
        private final long yearLength;
        private final long minDaysBetweenLunarEvents;

        public LunarTimeSettings(long dayLength, long yearLength, long minDaysBetweenLunarEvents) {
            this.dayLength = dayLength;
            this.yearLength = yearLength;
            this.minDaysBetweenLunarEvents = minDaysBetweenLunarEvents;
        }

        public long getDayLength() {
            return dayLength;
        }

        public long getYearLength() {
            return yearLength;
        }

        public long getMinDaysBetweenLunarEvents() {
            return minDaysBetweenLunarEvents;
        }
    }
}
