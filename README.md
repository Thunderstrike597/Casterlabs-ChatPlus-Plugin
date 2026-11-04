# Rumble Chat for Casterlabs Caffeinated

Adds Rumble chatters to Caffeinated's normal **Stream Chat** dock, next to Twitch/YouTube/etc.
Messages go through Caffeinated's own chat pipeline (`Koi.broadcastEvent`, the same path the
built-in Ko-fi integration uses), so they also reach chat widgets, the chat bot and your
Chat Spotlight plugin.

## Build & install

1. `mvn install` in this folder (or use IntelliJ's Maven panel → install).
2. Copy `target/RumbleChat.jar` into `%appdata%/casterlabs-caffeinated/plugins`.
3. Restart Caffeinated.

The SDK jar on repo.casterlabs.co, the Caffeinated stable release and the GitHub source all
ship different versions of Koi (Caffeinated's chat library). So the plugin never references Koi
classes directly: `RumbleKoiBridge` builds each event as Koi's JSON wire format and `KoiDispatch`
hands it to the running app's own `KoiEventType.get(...)`, found by name at runtime. Built and
tested against Caffeinated stable (`1.2-...-stable`, the `legacy` branch).

## Setup (Settings → Rumble Chat)

**Recommended: "Auto-detect my live stream"**
Paste your private Live Stream API URL from <https://rumble.com/account/livestream-api>
(it has `?key=...` in it). The plugin checks it every 15 s, connects to whichever stream is
live, and goes back to waiting when the stream ends. Nothing to change between streams.

**Alternative: "A specific stream / chat URL"**
Accepts any of:
- `https://rumble.com/chat/popup/123456789`
- `https://rumble.com/embed/v6abc12`
- `https://rumble.com/v6abc12-my-stream-title.html`
- a raw ID (`123456789` or `6abc12`)

Note: on Rumble every livestream is a new video with its own chat ID, so in this mode you'd
paste a new URL each stream. That's why auto-detect is the default.

### Display options
| Option | Default | |
|---|---|---|
| Show messages as platform | Rumble | Koi already has a `RUMBLE` platform. Caffeinated doesn't ship a Rumble logo yet, so the little platform icon next to names is blank. Pick "Custom Integration" to get the 🔧 icon instead. |
| Show Rants as donations | on | Rants appear as highlighted donation messages (USD). |
| Show Rumble badges | on | Verified / mod / premium etc. badge icons from Rumble. |
| Show messages sent before connecting | off | Rumble replays recent history on connect. |
| Post connect / disconnect notices | on | A small "Connected to Rumble chat" line in the feed. |

## The "Stream Chat + Rumble" dock

Add it from Caffeinated's dock picker. It's a copy of the stock Stream Chat dock (same
preferences, same look) whose send box can target **All / Twitch / YouTube / Rumble**.
- **All** sends through Caffeinated's normal "All" *and* to Rumble.
- **Rumble** sends only to Rumble.
- Auto-scroll only follows new messages while you're at the bottom; scroll up and it stays put
  (a ↓ button takes you back).
- Right-click a message to reply, copy, upvote or delete (where the platform supports it).
- Click a username to see that chatter's message history (avatar, badges, platform icon at the
  top; updates live). The back button (top-left) or Esc returns to the chat where you left it.

Rumble messages still reach Caffeinated's own chat globally (stock dock, OBS chat widgets, other
plugins like Chat Spotlight); the new dock just listens to the same feed.

### Rumble login (dock → cog icon → "Rumble account")
- **Browser session cookie**: paste your rumble.com `u_s` cookie. Encrypted on this PC.
- **Username & password**: the plugin logs in like rumble.com does and re-logs in when the
  session expires. The panel explains exactly what it stores (in bold). 2FA codes are asked for
  when Rumble needs them.

Secrets are encrypted with Windows DPAPI in `%APPDATA%\casterlabs-caffeinated\rumble-chat\account.json`
(only your Windows user can decrypt them) and are never written to Caffeinated's settings or logs.

### Rumble icon
Put `icon.png` (or `.svg/.webp/.gif/.jpg`) in `src/main/resources/assets/rumblechat/` before building.
The new dock shows it in the platform-icon spot; the stock chat and widgets get it as the first badge.

## How it works

- `rumble/RumbleLiveStreamApi` – official API, used only to find the live stream's ID.
- `rumble/RumbleChatClient` – reads Rumble's chat event stream
  (`https://web7.rumble.com/chat/api/chat/{id}/stream`), reconnects on drops,
  treats HTTP 204 as "chat ended".
- `RumbleKoiBridge` – converts Rumble users/messages/rants/deletions into Koi events.
- `RumbleChatPlugin` – settings tab and the connect/auto-detect loop.

Sending to Rumble works from the "Stream Chat + Rumble" dock (see above); the stock dock can't
be extended by plugins.
Rumble's chat stream endpoint is unofficial, so if Rumble changes it this is the file to fix.

## Not done yet (easy follow-ups)
- Rumble emotes (`:emote:`) render as plain text.
- From the stock dock's extras, not yet in the new dock: the ban/timeout/raid menu.
