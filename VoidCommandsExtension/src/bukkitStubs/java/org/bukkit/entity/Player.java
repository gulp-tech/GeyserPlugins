package org.bukkit.entity;
import org.bukkit.command.CommandSender;
import java.util.UUID;
public interface Player extends CommandSender {
    boolean isOnline();
    String getName();
    UUID getUniqueId();
}
