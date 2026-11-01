# FreeFire-Proxy-Monitor

Background Android app jo Free Fire ke network traffic ko monitor karta hai — VPN/Proxy ON hone se pehle aur baad ka data alag-alag log karta hai.

## Kaise kaam karta hai

1. App open karo → **START MONITOR** dabao.
2. Service background me chalti rahegi (notification dikhega).
3. Jab tak VPN/Proxy OFF hai → har 5 second me `BEFORE_PROXY` data log hota hai.
4. Jab aap Happ Proxy / koi VPN connect karte ho → auto detect → `AFTER_PROXY` section start.
5. Free Fire khelo.
6. Log file me before + after dono data milta hai.

## Log file location

```
Android/data/com.ashxmay.ffproxymonitor/files/ff_proxy_monitor_log.txt
```

File manager se open kar sakte ho (ya `adb pull`).

## Build APK (GitHub Actions)

Repo me workflow already hai.

1. Actions tab → **Build APK** → Run workflow
2. Artifact download karo → `ff-proxy-monitor-debug`
3. APK install karo (unknown sources allow karna padega)

## Manual build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions

- ACCESS_NETWORK_STATE (VPN detect)
- FOREGROUND_SERVICE (background chalne ke liye)
- POST_NOTIFICATIONS

## Package monitored

`com.dts.freefireth` (official Free Fire)

Agar package change ho to `MonitorService.kt` me update karna.

## Notes

- TrafficStats UID based hai — Free Fire installed hona chahiye.
- VPN detect NetworkCapabilities.TRANSPORT_VPN se hota hai (Happ Proxy / most VPNs cover).
- Headshot / aim assist claim verify karne ke liye before-after traffic + timing compare kar sakte ho.

ashxmay forged.
