package net.azisaba.jg.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.azisaba.jg.sdk.JunkGame;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ResourceUtility
{
    public static File getResource(JunkGame game, String name)
    {
        return new File(game.getDataFolder(), name);
    }

    public static YamlConfiguration getYamlResource(JunkGame game, String name)
    {
        return YamlConfiguration.loadConfiguration(ResourceUtility.getResource(game, name));
    }

    public static JsonElement getJsonResource(JunkGame game, String name)
    {
        try
        {
            return JsonParser.parseReader(new FileReader(ResourceUtility.getResource(game, name)));
        }
        catch (FileNotFoundException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void save(JunkGame game, String name, YamlConfiguration resource)
    {
        try
        {
            resource.save(ResourceUtility.getResource(game, name));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void save(JunkGame game, String name, JsonElement resource)
    {
        try
        {
            Path path = Paths.get(game.getDataFolder() + "/" + name);

            if (! Files.exists(path))
            {
                Files.createFile(path);
            }

            FileWriter writer = new FileWriter(path.toFile());
            writer.write(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(resource));
            writer.close();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static boolean exists(JunkGame game, String name)
    {
        Path path = Paths.get(game + "/" + name);
        return Files.exists(path);
    }
}
