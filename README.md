# Reclaimed Flip mod

A free, **unofficial** Fabric client mod for Hypixel SkyBlock. It shows Bazaar flip information from [Reclaimed Flip](https://reclaimedflip.com) in the game and tracks your own Bazaar profit.

> **Not affiliated with or endorsed by Hypixel or Mojang.**
> This mod is **not on Hypixel's approved modifications list**, and Hypixel's policy does not clearly allow mods that show calculated information. **Use it at your own risk.** Read [Hypixel's Allowed Modifications policy](https://support.hypixel.net/hc/en-us/articles/6472550754962/) first.

## What it does

- **Display only.** It reads and shows information. It never clicks, buys, sells, types or automates anything.
- **Alerts.** Shows Bazaar flip alerts from Reclaimed Flip in your chat.
- **Item tips.** Adds price lines to the tooltip of the item you are hovering.
- **Profit panel (HUD).** A small panel with session profit, bars for your last flips, flips per hour and your last flip. You can switch parts on and off.
- **Bazaar tracker.** Reads the `[Bazaar]` lines Hypixel already shows in your chat and matches your sells against your oldest buys (first in, first out). Everything stays on your PC.
- **`/rm` menu.** Type `/rm` to open the settings screen.

Auction and gear flips are **coming soon** and are not shown yet while the data is collected.

## What it sends

- It asks `reclaimedflip.com` for prices and alerts. It never talks to Hypixel itself and holds **no Hypixel API key**.
- Your tracker history and settings are saved **only on your PC**.
- **Optional, off by default:** sharing your Bazaar session (profit, number of flips and time) to the Top flippers page on the website. It needs a mod key that you make yourself on the website's account page, and the numbers are labelled as reported by the player. Nothing else is sent.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft **26.3**.
2. Download the mod `.jar` from the **Releases** page of this repository.
3. Put the `.jar` in your `mods` folder and start the game.
4. In game, type `/rm`.

## Status

Early test version. Expect rough edges. Please report problems on the Issues page with your Minecraft version and what you did.

## License

Source-available, **not open source**. You may download and run the released mod and read the code here. You may not copy, modify, redistribute or reuse it without written permission. See [LICENSE](LICENSE).
