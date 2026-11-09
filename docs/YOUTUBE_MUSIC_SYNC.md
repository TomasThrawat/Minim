# YouTube Music web session sync

Minim uses the MetroList-style YouTube Music web sign-in flow. The app opens Google's sign-in page in an app-owned WebView, reads the YouTube Music session cookie and page identity values after reaching music.youtube.com, then validates the session against YouTube Music's account-menu endpoint.

The session cookie is encrypted with Android Keystore and excluded from Android cloud-backup and device-transfer rules. Minim never reads or stores the Google password. No Android OAuth client ID or signing-certificate SHA-1 registration is required.

Liked-song reads and like/unlike mutations use YouTube Music's undocumented WEB_REMIX InnerTube interface. This is not a supported public YouTube Music API and may change without notice. Playlists created in Minim, listening statistics, and settings remain local.

A successful account-menu validation confirms sign-in, but a full device test is still required to confirm liked-song pagination and a like/unlike round trip. Pending like changes are retained until a fresh remote liked-songs read confirms the requested state; an HTTP success alone does not clear the queue.
