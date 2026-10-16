# Nothing in the app or the protocol module uses reflection; R8's defaults suffice.
# Keep line numbers so crash reports from users stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
