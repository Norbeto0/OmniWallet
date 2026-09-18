package dev.omniwallet.protocol.flipper

import dev.omniwallet.core.domain.Protocol

/**
 * Mapping from a [Protocol] to the Flipper application that emits it, and to
 * the directory its saved files live in.
 *
 * ## Getting the app name right
 *
 * `AppStartRequest.name` is handed straight to `loader_start`
 * (`applications/services/rpc/rpc_app.c`). The loader resolves it two different
 * ways (`applications/services/loader/loader.c`):
 *
 *  - internal apps match on **either** display name or appid:
 *    `strcmp(name, list[i].name) == 0 || strcmp(name, list[i].appid) == 0`
 *  - external apps match on the **display name only**:
 *    `strcmp(FLIPPER_EXTERNAL_APPS[i].name, app_name) == 0`
 *
 * All five protocol apps are `FlipperAppType.MENUEXTERNAL`, so only the display
 * name works, and the comparison is case-sensitive. That makes the plausible
 * guesses -- "Nfc", "LfRfid", "IButton" -- all silently wrong; they come back
 * as `ERROR_APP_CANT_START`. The names below are taken from each app's
 * `application.fam`, and verified identical in official firmware and Momentum.
 *
 * [appId] is kept as a fallback: a custom firmware could rename an app, and
 * retrying with the appid costs one round trip and covers internal-app builds.
 */
enum class FlipperApp(
    val protocol: Protocol,
    val appName: String,
    val appId: String,
    val directory: String,
    val fileExtension: String,
) {
    NFC(Protocol.NFC, "NFC", "nfc", "/ext/nfc", ".nfc"),
    RFID_125K(Protocol.RFID_125K, "125 kHz RFID", "lfrfid", "/ext/lfrfid", ".rfid"),
    SUBGHZ(Protocol.SUBGHZ, "Sub-GHz", "subghz", "/ext/subghz", ".sub"),
    INFRARED(Protocol.INFRARED, "Infrared", "infrared", "/ext/infrared", ".ir"),
    IBUTTON(Protocol.IBUTTON, "iButton", "ibutton", "/ext/ibutton", ".ibtn");

    companion object {
        fun forProtocol(protocol: Protocol): FlipperApp =
            entries.first { it.protocol == protocol }

        /** Which app owns a path like `/ext/nfc/office.nfc`, if any. */
        fun forPath(path: String): FlipperApp? =
            entries.firstOrNull { path.startsWith(it.directory + "/") }

        /** Every directory worth enumerating during discovery. */
        val assetDirectories: List<String> = entries.map { it.directory }
    }
}
