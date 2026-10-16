package ru.FaralMC.talismans;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Мягкая привязка к Vault Economy через рефлексию (как Hooks в FaralTab):
 * плагин работает и без Vault — просто покупка талисманов будет недоступна.
 */
public final class VaultHook {

    private Object economy; // net.milkbowl.vault.economy.Economy
    private Method getBalance;
    private Method withdraw;
    private boolean tried;

    public boolean available() {
        if (economy == null && !tried) {
            tried = true;
            try {
                Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
                Object registration = Bukkit.getServicesManager().getRegistration(ecoClass);
                if (registration != null) {
                    economy = registration.getClass().getMethod("getProvider").invoke(registration);
                    getBalance = ecoClass.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                    withdraw = ecoClass.getMethod("withdrawPlayer", org.bukkit.OfflinePlayer.class, double.class);
                }
            } catch (Throwable ignored) {
                economy = null;
            }
        }
        return economy != null;
    }

    public double balance(Player player) {
        if (!available()) {
            return 0;
        }
        try {
            Object result = getBalance.invoke(economy, player);
            return result instanceof Number ? ((Number) result).doubleValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** @return true, если деньги успешно списаны. */
    public boolean withdraw(Player player, double amount) {
        if (!available() || amount <= 0) {
            return false;
        }
        try {
            Object response = withdraw.invoke(economy, player, amount);
            if (response == null) {
                return false;
            }
            Object success = response.getClass().getMethod("transactionSuccess").invoke(response);
            return Boolean.TRUE.equals(success);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
