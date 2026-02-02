# CoinCash_Plugin
Minecraft plugin for trading items for coins. Item economy like.

Dependency: https://github.com/FoxUshiha/DC-Coin-Bot

Oficial Website and API URL: https://bank.foxsrv.net/

Version: 1.20+ Minecraft (Spigot, Paper, Purpur, Forge+Plugins, and other forks);

Oficial Discord Bot: https://discord.com/oauth2/authorize?client_id=1391067775077978214

Chrome Extension: https://chromewebstore.google.com/detail/coin-bank/lbojdaalcfajcjphlpphjchkenjpbemf


Default Config (Pt-BR):

```
# Configuração da API
API: "https://bank.foxsrv.net/"

# Configurações de moeda
Worth: 0.01  # Valor de 1 item em coins (custo de compra)
Sell: 0.01 # Valor de venda de 1 item em coins (valor de revenda)
amount: 1    # Quantidade de itens por unidade de coin

# Item que será dado/vendido
item: "GOLD_INGOT"

# Card do servidor (DEVE ser configurado)
Card: "SEU_CARD_AQUI"

# Cooldown em milissegundos
cooldown_ms: 1000

# Modo de compatibilidade com mods (true para itens de mods)
mod_compatibility: true

```

Commands:

- /coin reload
- /coin help
- /coin buy amount card
- /coin sell amount
- /coin id card
- /coin info

Permissions:

- coincash.user (default)
- coincash.admin (OP)
