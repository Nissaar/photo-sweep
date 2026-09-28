# SPDX-FileCopyrightText: 2026 Nissaar
# SPDX-License-Identifier: AGPL-3.0-or-later

# kotlinx.serialization and OkHttp ship their own R8 rules inside their jars, and
# R8 applies them by itself. The copies that used to live here, and the Tink rules
# left over from androidx.security.crypto, which the app no longer uses, only made
# it harder to see which rules this app actually needs.

# Release builds should carry no logging at all.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
