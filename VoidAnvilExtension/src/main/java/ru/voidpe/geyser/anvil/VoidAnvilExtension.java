package ru.voidpe.geyser.anvil;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.util.List;

/**
 * VoidAnvilExtension — Bedrock-only вход в наковальню VoidPE.
 *
 * <p>Расширение регистрирует команду {@code /voidanvil anvil}, показывает
 * игроку нативную Bedrock-форму Cumulus и по кнопке выполняет серверную
 * команду {@code /vench anvil}. Само объединение предметов делает плагин
 * VoidEnchants на Purpur: так результат не зависит от ванильной проверки
 * Bedrock-клиента, которая показывает красный X на кастомных книгах.</p>
 */
public final class VoidAnvilExtension implements Extension {

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        logger().info("VoidAnvilExtension включён: Bedrock-команда /voidanvil anvil -> /vench anvil");
    }

    @Subscribe
    public void onDefineCommands(GeyserDefineCommandsEvent event) {
        Command command = Command.<GeyserConnection>builder(this)
                .source(GeyserConnection.class)
                .name("anvil")
                .aliases(List.of("open"))
                .description("Bedrock-наковальня VoidPE для кастомных зачарований")
                .playerOnly(true)
                .bedrockOnly(true)
                .executor((source, cmd, args) -> openForm(source))
                .build();
        event.register(command);
    }

    private void openForm(GeyserConnection connection) {
        SimpleForm form = SimpleForm.builder()
                .title("Наковальня VoidPE")
                .content("Это Bedrock-наковальня для кастомных книг и кирок VoidEnchants.\n\n"
                        + "Обычный экран наковальни в Bedrock не понимает кастомные зачарования "
                        + "и показывает красный X, поэтому объединение считает сервер.\n\n"
                        + "Положите два предмета в слоты меню: инструмент + книгу, "
                        + "книгу + книгу или два одинаковых инструмента.")
                .button("Открыть наковальню")
                .button("Как пользоваться")
                .validResultHandler(response -> {
                    if (response.clickedButtonId() == 0) {
                        connection.sendCommand("vench anvil");
                    } else {
                        connection.sendMessage("Нажмите на обычную наковальню в мире — откроется Bedrock-наковальня. "
                                + "Для переименования и ванильных объединений присядьте перед нажатием.");
                    }
                })
                .build();
        connection.sendForm(form);
    }
}
