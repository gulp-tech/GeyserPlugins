block_palette.1_21_110.nbt — палитра блоков Bedrock 1.21.110 из открытых ресурсов Geyser
(https://github.com/GeyserMC/Geyser, коммит 4d1c5f3eb325883c81e1fd067994ad8110484717, файл
core/src/main/resources/bedrock/block_palette.1_21_110.nbt). Geyser распространяется по лицензии MIT
(Copyright (c) 2019-2025 GeyserMC, http://geysermc.org), текст лицензии — в репозитории Geyser.

Зачем файл здесь: расширение отдаёт клиентам протоколов 844–898 (Bedrock 1.21.110–1.21.130) маппинги
блоков, пересчитанные по этой палитре. Без неё клиент получает runtime id из палитры 26.30 и рисует
мир «пластинами». Файл не изменяется — только читается при старте Geyser.
