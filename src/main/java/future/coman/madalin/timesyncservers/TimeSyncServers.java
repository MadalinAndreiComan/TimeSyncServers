package future.coman.madalin.timesyncservers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TimeSyncServers implements ModInitializer {

    @Getter
    private static boolean isServer = false;
    public static final String filePath = "config.json";
    public static final String pathToClientClass = "net.minecraft.client.Minecraft";
    public static JedisPool redisServer;

    @Override
    public void onInitialize() {
        timeSyncServers();
    }

    public void serverLogic() {
        ServerInfo serverInfo = ServerInfo.readConfigFile();

        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            // Setup connection to server
            redisServer = new JedisPool(serverInfo.pathTORedisServer, serverInfo.redisServerPort, serverInfo.redisUser, serverInfo.redisPassword);

            // Sync time from Redis server if it is not master
            if (!serverInfo.isMaster)
                ServerInfo.syncTime(server);
        });

        // If it is master, send current time to Redis server
        if (serverInfo.isMaster)
        {
            ServerTickEvents.START_WORLD_TICK.register(new SendTimeToServer(serverInfo.desiredSendInterval));
        }
    }

    static class SendNoMasterToServer implements ServerLifecycleEvents.ServerStopping{

        @Override
        public void onServerStopping(MinecraftServer server) {
            try (Jedis jedis = redisServer.getResource())
            {

                jedis.set("isMasterOn", "1");
            }
        }
    }

    static class SendTimeToServer implements ServerTickEvents.StartWorldTick {
        private static int desiredSendRate;
        public static int reduceSendRate;

        @Override
        public void onStartTick(ServerLevel world) {
            // Reduce to only send every `desiredSendRate` ticks
            reduceSendRate++;
            if (reduceSendRate < desiredSendRate)
                return;

            reduceSendRate = 0;

            try (Jedis jedis = redisServer.getResource()) {
                jedis.set("time", Long.valueOf(world.getDayTime()).toString());
                System.out.println(world.getDayTime());
            }

        }

        public SendTimeToServer(int desiredSendRate) {
            SendTimeToServer.reduceSendRate = 0;
            SendTimeToServer.desiredSendRate = desiredSendRate;
        }
    }

    public static synchronized void writeFile(String directory, String fileName, String content) {
        Path path = Paths.get(directory, fileName);
        File file = path.toFile();
        file.getParentFile().mkdirs();

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException error) {
                System.out.println(error);
                System.out.println("Could not create file " + fileName + " in directory " + directory);
                return;
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
            writer.write(content);
        } catch (Exception error) {
            System.out.println(error);
            System.out.println("Could not write to file " + fileName + " in directory " + directory);
        }
    }

    public static synchronized String readFile(String directory, String fileName) {
        Path path = Paths.get(directory, fileName);
        StringBuilder json = new StringBuilder();
        File file = path.toFile();

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException error) {
                System.out.println(error);
                System.out.println("Could not create file " + fileName + " in directory " + directory);
                return "";
            }
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String curLine = reader.readLine();
            while (curLine != null) {
                json.append(curLine).append('\n');
                curLine = reader.readLine();
            }

            return json.toString().strip();
        } catch (Exception error) {
            System.out.println(error);
            System.out.println("Could not read file " + fileName + " in directory " + directory);
            return "";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    static class ServerInfo {
        public String pathTORedisServer = "example.com";
        public int redisServerPort = 12345;
        public String redisUser = "default";
        public String redisPassword = "password";
        public boolean isMaster = false;
        public int desiredSendInterval = 20;

        public static ServerInfo readConfigFile() {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = readFile(".", filePath);

            if (json.isEmpty()) {
                writeFile(".", filePath, gson.toJson(new ServerInfo()));
                json = readFile(".", filePath);
            }

            return gson.fromJson(json, ServerInfo.class);
        }

        public static void syncTime(MinecraftServer server) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation("overworld")));

            try (Jedis jedis = redisServer.getResource()) {
                long time = Long.parseLong(jedis.get("time"));
                level.setDayTime(time);
                System.out.println(time);
            }
        }
    }

    void timeSyncServers() {
        try {
            Class.forName(pathToClientClass);
            TimeSyncServers.isServer = false;
        } catch (Throwable e) {
            TimeSyncServers.isServer = true;
            serverLogic();
        }
    }
}
