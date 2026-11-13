# Zeekr wireless CarPlay — reading the head-unit hotspot credentials

Reference + design notes for feeding the head unit's SoftAP (hotspot) SSID and
passphrase to the iPhone for **wireless** CarPlay on the Zeekr 001 (and other Zeekr
head units). Derived from a clean-room analysis of the stock/proprietary CarPlay
receiver (`opencarplay` / "ZeeCarplay", `carplay_v1.1.apk`); this document records
the **mechanism and the platform facts**, not copied code.

---

## 1. Why this is needed

For **wireless** CarPlay the phone has to join the head unit's Wi-Fi hotspot
(SoftAP). The receiver must therefore:

1. Read the head unit's current SoftAP **SSID + passphrase**.
2. Hand them to the iPhone (over the iAP2/BT pairing channel, or shown as a QR for
   manual join).
3. Optionally make the hotspot **discoverable as CarPlay-capable** so the phone
   auto-joins (vendor information element — see §6).

The crux is that reading the SoftAP configuration is a **privileged** operation.

---

## 2. Reference mechanism (opencarplay / ZeeCarplay)

The proprietary app resolves everything by **reflection against the raw
`IWifiManager` binder**, not the public `WifiManager`. Classes involved:
`com.opencarplay.app.network.HotspotJoinPlatform` (+ `$Companion`, `HotspotJoinFirmware`,
`HotspotJoinCapability`, `HotspotJoinElement`). The result is surfaced to the engine
as `EngineHotspotCredentials { ssid, passphrase, passphraseKnown }` and consumed via
`Engine.getNetwork().readHotspotCredentials(ctx)` / `.hotspotEnabled(ctx)`.

### 2.1 Acquiring the service (`HotspotJoinPlatform.Companion.connect()`)

```text
require(Build.VERSION.SDK_INT >= 33)

binder = ServiceManager.getService("wifi")                       // reflection
wifi   = IWifiManager.Stub.asInterface(binder)                   // reflection
        // -> talks to the wifi system service binder DIRECTLY,
        //    bypassing the android.net.wifi.WifiManager wrapper
```

### 2.2 Resolving the hidden methods (`HotspotJoinPlatform.<init>`)

```text
configType  = Class.forName("android.net.wifi.SoftApConfiguration")
builderType = Class.forName("android.net.wifi.SoftApConfiguration$Builder")
elementType = Class.forName("android.net.wifi.ScanResult$InformationElement")

getConfig = wifi.class.getMethod("getSoftApConfiguration")                    // READ
setConfig = wifi.class.getMethod("setSoftApConfiguration", configType, String)// WRITE
getState  = wifi.class.getMethod("getWifiApEnabledState")                     // STATE
```

### 2.3 The operations

| Method        | Call                                              | Meaning |
|---------------|---------------------------------------------------|---------|
| `read()`      | `getSoftApConfiguration()`                         | returns `SoftApConfiguration` (holds SSID + passphrase) |
| `hotspotOff()`| `getWifiApEnabledState()`                          | `11` = `WIFI_AP_STATE_DISABLED` → off; `13` = `WIFI_AP_STATE_ENABLED` → on |
| `write(cfg)`  | `setSoftApConfiguration(cfg, "com.android.shell")` | writes config back; note the **`"com.android.shell"`** attribution tag → calls run from a privileged/shell context |

SSID + passphrase are then read off the returned `SoftApConfiguration`
(`getWifiSsid()` / `getSsid()`, `getPassphrase()`) and packed into
`EngineHotspotCredentials`.

---

## 3. Permissions & privilege model (the important part)

`IWifiManager.getSoftApConfiguration()` is gated by
**`NETWORK_SETTINGS`** (or `NETWORK_SETUP_WIZARD`). A normal app gets
`SecurityException`. Two independent facts decide whether the call succeeds:

### 3.1 `NETWORK_SETTINGS` is a **signature** permission

In AOSP:

```xml
<permission android:name="android.permission.NETWORK_SETTINGS"
            android:protectionLevel="signature" />
```

`signature` (not `signature|privileged`) means it is granted **purely by signing
cert match** — no `/etc/permissions` priv-app allowlist is required. An app signed
with the **platform key** that declares it in the manifest gets it granted, and the
call passes — **without** needing the system UID.

### 3.2 System UID / root bypass all permission checks

`PermissionManager` short-circuits for the system app id and root:

```java
if (appId == Process.SYSTEM_UID || appId == Process.ROOT_UID)
    return PERMISSION_GRANTED;   // every permission check passes
```

So if the app runs as `android.uid.system`, **no permission declaration is needed**
at all — `getSoftApConfiguration()` passes regardless of the manifest.

### 3.3 Hidden-API policy

Reflection to `IWifiManager` / `SoftApConfiguration` is on the hidden-API
restricted list. **Platform-signed and system apps are exempt** from hidden-API
enforcement; ordinary apps are not. This reinforces §3.1/§3.2: the feature is only
reachable from a platform-signed or system context.

### 3.4 Answer to "do I need to declare the permissions at system UID?"

- **Running as `android.uid.system`:** **No.** `NETWORK_SETTINGS` /
  `NETWORK_SETUP_WIZARD` are auto-granted by the UID bypass; declaring them is a
  harmless no-op.
- **Platform-signed but NOT system UID:** **Declare `NETWORK_SETTINGS`** — it is
  granted by signature match and is sufficient on its own.
- **`NETWORK_SETUP_WIZARD`:** `protectionLevel="signature|setup"`, only grantable to
  the designated Setup Wizard. **Do not declare it** — a normal/priv app never gets
  it via the manifest, and `getSoftApConfiguration()` accepts `NETWORK_SETTINGS`
  alone.

---

## 4. Build strategy — one build for all Zeekrs

The 001 has the platform key (system UID possible); other Zeekrs do not. Keep a
**single build** that degrades gracefully.

### 4.1 Do NOT use `sharedUserId="android.uid.system"`

- It forces **platform signing on every install**; on a non-platform-signed Zeekr
  the APK fails to install (signature mismatch).
- `android:sharedUserId` is **deprecated** since API 29.
- Prefer **platform-signing + `NETWORK_SETTINGS` declaration** (no shared UID). That
  installs everywhere and simply stays ungranted where the signature doesn't match.

### 4.2 Capability is decided by **signature**, not by code or edition

- One manifest declares `NETWORK_SETTINGS` (harmless when ungranted).
- Same APK, signed with the **platform key** → deployed to 001 → permission granted
  → hotspot read works.
- Same APK, signed with a **normal key** → deployed to other Zeekrs → permission
  ungranted → graceful fallback.

### 4.3 Gate at **runtime**, not with a compile flag

```text
try {
    binder = ServiceManager.getService("wifi")
    wifi   = IWifiManager.Stub.asInterface(binder)
    cfg    = wifi.getSoftApConfiguration()
    -> read SSID / passphrase, hand to engine
} catch (SecurityException | ReflectiveOperationException | LinkageError) {
    -> return null; caller falls back to manual entry / existing flow
}
```

One codebase covers both the platform-signed 001 and every other Zeekr.

### 4.4 Manifest

```xml
<uses-permission android:name="android.permission.NETWORK_SETTINGS" />
<!-- Do NOT add NETWORK_SETUP_WIZARD. Do NOT set sharedUserId. -->
```

---

## 5. Reading SSID / passphrase across API levels

`SoftApConfiguration` is API 30+. SSID accessor changed across versions, so probe
reflectively:

| What | API 30–31 | API 31+ (preferred) |
|------|-----------|----------------------|
| SSID | `getSsid() : String` (may be quoted `"name"`) | `getWifiSsid() : WifiSsid` → `getBytes()` (UTF-8) or `toString()` |
| Passphrase | `getPassphrase() : String?` (null for open networks) | same |
| Security | `getSecurityType() : int` | same |

Recommended order: try `getWifiSsid()` → `WifiSsid.getBytes()` decoded UTF-8; on
failure fall back to `getSsid()` and strip surrounding quotes. Treat a null/blank
passphrase as **open / unknown** (`passphraseKnown = false`).

If the AP is **off** (`getWifiApEnabledState() == 11`), the config may still be
readable, but the phone can't join until it is enabled. Options: prompt the user to
enable the hotspot, or (privileged) enable it via the same path
(`startTetheredHotspot` / toggling SoftAP) — more invasive, treat as a later phase.

---

## 6. Vendor information element "repair" (advanced / phase 2)

For the iPhone to auto-discover the hotspot as **CarPlay-capable**, the reference
app patches the SoftAP beacon with an **Apple vendor-specific information element**:

- Element id **221** (`0xDD`, vendor-specific IE).
- Payload begins with Apple OUI **`00:A0:40`**; observed bytes:
  `00 A0 40 00 00 02 00 21`.
- Flow (`HotspotJoinPlatform.repaired()`, API ≥ 33, only when
  `isUserConfiguration()`): read `getVendorElements()`, `getChannel()`,
  `getBands()`; merge the Apple IE; rebuild via `SoftApConfiguration$Builder`
  (`setVendorElements(...)`), and write back with
  `setSoftApConfiguration(cfg, "com.android.shell")`. A capability snapshot
  (`allows(channel)`) guards channels the radio can't use.

This is **not required just to read** the credentials; it is needed for seamless
wireless auto-join. Implement after the read path works.

---

## 7. Why the reference app has a firmware-fingerprint layer

`HotspotJoinFirmware.current()` builds a fingerprint from:

- `Build.VERSION.SDK_INT`, `Build.FINGERPRINT`, `Build.VERSION.INCREMENTAL`
- `SystemProperties`: `ro.vendor.build.fingerprint`,
  `ro.vendor.build.version.incremental`, `ro.bootimage.build.fingerprint`
- **SHA-256 of `framework-wifi.jar`** (`/apex/com.android.wifi/javalib/framework-wifi.jar`,
  fallback `/system/framework/framework-wifi.jar`)

Purpose: the hidden `SoftApConfiguration` / `IWifiManager` shapes vary between
builds, so it selects per-firmware reflection behavior. **For ZeePlay we do not need
this generality** — target the 001's exact API and wrap everything in `try/catch`.
Keep the firmware map only if we later support many Zeekr firmwares.

---

## 8. Proposed clean-room design for ZeePlay

Mirror the existing edition-seam pattern (as used for the HUD / cluster outputs).

### 8.1 Seam

```
common/src/main/java/.../VehicleHotspot.kt           // interface + data class
common/src/standard/java/.../VehicleHotspotFactory.kt// createHotspotReader() = null
common/src/zeekr/java/.../VehicleHotspotFactory.kt   // = ZeekrHotspotReader(ctx)
common/src/zeekr/java/.../ZeekrHotspotReader.kt      // reflection-only impl
```

### 8.2 Contract

```kotlin
data class HotspotCredentials(
    val ssid: String,
    val passphrase: String?,     // null => open / unknown
    val passphraseKnown: Boolean,
    val apEnabled: Boolean,      // from getWifiApEnabledState() == 13
)

interface VehicleHotspot {
    /** Returns null when unprivileged/unavailable; caller falls back. */
    fun read(): HotspotCredentials?
}
```

### 8.3 `ZeekrHotspotReader.read()` outline

1. `ServiceManager.getService("wifi")` → `IWifiManager.Stub.asInterface(binder)`
   (reflection).
2. `getSoftApConfiguration()` → config object.
3. `getWifiApEnabledState()` → `apEnabled = (state == 13)`.
4. SSID via `getWifiSsid()`/`getSsid()` (see §5); passphrase via `getPassphrase()`.
5. Everything in `try/catch (SecurityException | ReflectiveOperationException |
   LinkageError)` → return `null`.

### 8.4 Wiring

- The CarPlay/AirPlay engine calls `createHotspotReader(ctx)?.read()`. On `null`,
  keep the current manual/BT path. On success, feed SSID + passphrase to the phone
  for wireless join.
- Gate **only** on `read() != null` (capability), never on `BuildConfig.IS_ZEEKR`
  for the privilege itself — the seam already restricts the reader to the zeekr
  edition; privilege is a runtime property of the signature.

---

## 9. Checklist / decisions

- [x] Read path: `ServiceManager → IWifiManager → getSoftApConfiguration` (reflection).
- [x] Permission: declare **`NETWORK_SETTINGS`** only; **not** `NETWORK_SETUP_WIZARD`.
- [x] **No** `sharedUserId="android.uid.system"`; rely on platform signing + signature grant.
- [x] Capability decided by signing key; one build for all Zeekrs.
- [x] Runtime `try/catch` fallback; not a compile flag.
- [ ] SSID accessor across API 30/31+ (`getWifiSsid` vs `getSsid`).
- [ ] Enable-AP-if-off policy (prompt vs privileged enable) — later phase.
- [ ] Apple vendor-IE repair for auto-discovery (§6) — later phase.
- [ ] Clean-room: implemented from Android platform facts, no code copied from the
      reference APK.

---

*Notes compiled from reverse-engineering the stock receiver for interoperability.
All API names (`IWifiManager`, `SoftApConfiguration`, `getSoftApConfiguration`,
`NETWORK_SETTINGS`, etc.) are standard Android platform symbols.*
