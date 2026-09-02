# MobileTina v2rayNG 2.2.6 migration

## Base and branch
- Exact upstream base commit: `15b4fff8e45da9bc0acaa5cc1d80a1d3531e8712` (`up 2.2.6`).
- Development branch: `agent/mobiletina-2.2.6-port`.
- Technical comparison base: `agent/v2rayng-2.2.6-base`.
- `master` is not a migration target and must not be merged without explicit user approval.
- Application ID: `com.v2ray.mobiletina`.
- Namespace: `com.v2ray.ang`.
- Version name: `2.2.6`.
- MobileTina base version code: `744` so the Playstore ARMv7 variant upgrades the previous MobileTina install instead of being treated as a downgrade.

## Core strategy
The 2.0.15 Core was not copied into this branch. MobileTina runs on the native v2rayNG 2.2.6 architecture (`CoreServiceManager`, `CoreConfigManager`, `CoreTestService`, matching AndroidLibXrayLite/libv2ray). MobileTina compatibility facades only bridge old UI call sites to the 2.2.6 Core APIs.

Smart Connect uses the native 2.2.6 Real Ping batch service. One server connects directly without starting the batch timer. Multiple servers run cancellable Real Ping, wait up to six seconds, select the lowest positive result available, sort, and connect.

## Preserved MobileTina UX/features
- Auto is the default page; Manual remains available with directional swipe and dedicated mode buttons.
- Exact user artwork is kept byte-for-byte in `drawable-nodpi`, with no vector conversion, recolor, tint, or recompression.
- Auto artwork mapping: white=idle, yellow=connecting, blue=connected, red=failed; Auto FAB 236dp. The Auto content top padding is 94dp so the large FAB sits slightly lower on the page.
- Manual artwork mapping: stop=VPN off, fab=VPN on; Manual FAB 92dp; Smart Connect button 49dp high with no emoji in its label.
- Manual list shows server name + ping only. Editing, sharing, deleting and drag/reorder are not exposed. Selected server is visibly highlighted.
- Subscription card remains Auto-only and shows used/total and days remaining when `subscription-userinfo` metadata exists. In Light mode its progress colors are inverted for legibility; while VPN is connected the card is raised slightly.
- Subscription metadata refresh supports the running local proxy first with direct-network fallback.
- Default subscription title is normalized to `instagram : mobile.tina`.
- Synthetic Default/All groups are normally filtered from MobileTina. Direct imported configs that would otherwise land in the hidden DEFAULT group are migrated into a visible local MobileTina group so even a single scanned/imported server appears immediately.
- First run requests Camera permission, then VPN authorization; Camera denial does not block the app and VPN authorization does not auto-connect by itself.
- Resume checks validated internet and refreshes subscriptions with a guard; when internet is unavailable the Persian warning is shown as an auto-dismissing three-second dialog instead of a toast.
- QR scanner starts scanning immediately by default for new installs.
- Hidden subscription reveal remains a real ~10-second hold and provides QR, subscription-link copy, and Copy All Configs including Custom JSON. Pending reveal callbacks are cancelled when subscription tabs change or rebuild, and the selected tab is revalidated immediately before the dialog opens so an old tab cannot reveal its QR after navigation. A one-server group reveals the actual server URI QR and a copy-server-link action even without a subscription URL.
- Public share/edit/export actions remain hidden from MobileTina.
- Drawer is reduced to About Store, Per App Proxy, Settings, and Remove VPN; legacy technical entries stay hidden.
- Remove VPN stops service, clears app subscriptions/configs/selection, and does not revoke system permissions.
- JSON `_comment` expiration retains exact-alarm/WorkManager fallback and boot/package-update recovery, producing `اشتراک منقضی شد` on expiry.
- Continuous VPN sessions are limited to 24 hours. The limiter is now scheduled/cancelled in the 2.2.6 Core lifecycle, not only from MainActivity.
- VPN notification keeps Stop but removes Restart.
- The ordinary `Start services` toast is suppressed while the proxy-sharing warning is retained.
- Android 16+ local-network permission behavior from v2rayNG 2.2.6 is preserved when proxy sharing is enabled.
- Launcher shortcut metadata and home-screen widget are removed in the Playstore manifest overlay.
- Launcher branding uses standard Android `mipmap` resources: legacy PNG icons for mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi plus adaptive foreground/background resources for Android 8+; the Playstore manifest references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`. The adaptive foreground canvas is reduced to 76% of its previous scale to give the red logo more breathing room inside launcher masks.
- `Enable double column display` and `Auto connect at start up` are removed from Settings and their stale stored values are forced off on upgrades.
- Auto status text uses `فیلترشکن خاموش است` while stopped and `متصل شد` while connected; numeric Auto ping is displayed as `پینگ : N`.
- Direct config import result uses Persian `تعداد N کانفیگ اضافه شد`; app-owned Cancel dialog buttons use `بیخیال` and generic import failure is shown as `خطا`.
- Manual Smart Connect button is black with white text in Light mode and white with black text in Night mode.
- The Manual bottom dock follows the old MobileTina composition: the 92dp FAB floats 28dp above the dock edge, the Smart Connect button sits below it, and the selected-server row stays at the bottom. FAB outline/elevation effects are disabled so no stray Light-mode shadow appears.
- A two-flavor Android Lint audit plus source-reference scan removed only confirmed unused upstream raster resources: all density variants of `ic_stat_name_black.png` and both day/night `nav_header_bg.png` resources. All MobileTina-added or modified artwork is explicitly protected from cleanup.
- Non-default locale packs were removed after an audit. Arabic, Bengali, Bakhtiari, Persian, Russian, Vietnamese, Simplified Chinese, and Traditional Chinese translation directories are gone; only the default `values/` resource set remains. Non-language qualifiers such as `values-night` and `values-sw360dp-v13` are preserved.
- Custom `text.ttf` remains the app font through the MobileTina application theme.

## Regression rules
- Do not replace the native 2.2.6 Core/Real Ping implementation with 2.0.15 or 2.3.3 internals.
- Do not alter user artwork bytes.
- Build ARMv7 before declaring a migration change successful.
- Phone QA must verify Real Ping against the user's known server set, single-server direct connect, direct import visibility, Auto/Manual artwork and spacing, QR import/update metadata, expiry behavior, and 24-hour scheduling.
