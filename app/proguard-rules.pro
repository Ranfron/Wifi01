# WifiP2P v2 - ProGuard / R8 rules
#
# The app does not use reflection or serialization (the transfer protocol is a hand-written binary
# format), so no keep rules are required. AndroidX / Material ship their own consumer rules.

# Readable crash reports: keep line numbers, hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
